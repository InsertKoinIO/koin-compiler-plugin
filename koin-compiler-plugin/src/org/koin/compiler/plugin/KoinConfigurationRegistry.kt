package org.koin.compiler.plugin

/**
 * Shared registry for @Configuration module class names with their configuration labels.
 * Uses System properties to share data between FIR and IR phases (which run in different classloaders).
 *
 * FIR phase discovers modules (local + from JARs via hint functions) and stores in System property.
 * IR phase reads the discovered modules from System property and filters by requested labels.
 *
 * Storage format: "label1:module1,module2;label2:module1,module3"
 * - Labels are separated by ";"
 * - Each label entry is "label:module1,module2"
 * - Modules with multiple labels appear under each label
 */
object KoinConfigurationRegistry {
    // Default label used when @Configuration has no explicit labels
    const val DEFAULT_LABEL = "default"

    // System property key for storing discovered modules with labels
    private const val MODULES_PROPERTY = "koin.plugin.configuration.modules"

    // System property key for storing scan packages per module
    // Format: "moduleFqName1=pkg1,pkg2;moduleFqName2=pkg3"
    private const val SCAN_PACKAGES_PROPERTY = "koin.plugin.configuration.scanpackages"

    /**
     * Register a module with its configuration labels (called by FIR).
     * Stores in System property to survive classloader boundaries.
     *
     * @param moduleClassName Fully qualified class name of the module
     * @param labels Configuration labels (uses DEFAULT_LABEL if empty)
     */
    fun registerModule(moduleClassName: String, labels: List<String>) {
        val effectiveLabels = labels.ifEmpty { listOf(DEFAULT_LABEL) }
        synchronized(System.getProperties()) {
            val labelMap = parseProperty()
            for (label in effectiveLabels) {
                val modules = labelMap.getOrPut(label) { mutableSetOf() }
                modules.add(moduleClassName)
            }
            System.setProperty(MODULES_PROPERTY, serializeProperty(labelMap))
        }
    }

    /**
     * Register a local @Configuration module (called by FIR).
     */
    fun registerLocalModule(moduleClassName: String, labels: List<String> = listOf(DEFAULT_LABEL)) {
        registerModule(moduleClassName, labels)
    }

    /**
     * Register a module discovered from JAR hint function (called by FIR).
     * The label is extracted from the hint function name (e.g., "configuration_test" -> "test").
     */
    fun registerJarModule(moduleClassName: String, label: String = DEFAULT_LABEL) {
        registerModule(moduleClassName, listOf(label))
    }

    /**
     * Register the @ComponentScan packages for a module (called by FIR).
     * Stores in System property so IR can read them for cross-Gradle-module siblings.
     *
     * @param moduleClassName Fully qualified class name of the module
     * @param scanPackages Packages scanned by @ComponentScan (empty = module's own package)
     */
    fun registerScanPackages(moduleClassName: String, scanPackages: List<String>) {
        synchronized(System.getProperties()) {
            val scanMap = parseScanPackagesProperty()
            scanMap[moduleClassName] = scanPackages.toMutableList()
            System.setProperty(SCAN_PACKAGES_PROPERTY, serializeScanPackages(scanMap))
        }
    }

    /**
     * Get the @ComponentScan packages for a module (called by IR).
     * Returns null if the module has no registered scan packages (i.e., no @ComponentScan).
     * Returns empty list if @ComponentScan with no explicit packages (uses module's own package).
     */
    fun getScanPackages(moduleClassName: String): List<String>? {
        val scanMap = parseScanPackagesProperty()
        return scanMap[moduleClassName]
    }

    /**
     * Get module class names for specific configuration labels (called by IR).
     * A module is included if it has ANY of the requested labels.
     *
     * @param labels Configuration labels to filter by (uses DEFAULT_LABEL if empty)
     * @return Set of module class names matching any of the labels
     */
    fun getModuleClassNamesForLabels(labels: List<String>): Set<String> {
        val effectiveLabels = labels.ifEmpty { listOf(DEFAULT_LABEL) }
        val labelMap = parseProperty()
        return effectiveLabels.flatMap { label ->
            labelMap[label] ?: emptySet()
        }.toSet()
    }

    /**
     * Get all discovered module class names regardless of labels (called by IR).
     * Used for backward compatibility.
     */
    fun getAllModuleClassNames(): Set<String> {
        return parseProperty().values.flatten().toSet()
    }

    /**
     * Get all registered labels.
     */
    fun getAllLabels(): Set<String> {
        return parseProperty().keys
    }

    /**
     * Clear the registry (useful for testing).
     */
    fun clear() {
        System.clearProperty(MODULES_PROPERTY)
        System.clearProperty(SCAN_PACKAGES_PROPERTY)
    }

    // Parse "label1:mod1,mod2;label2:mod1,mod3" into Map<String, MutableSet<String>>
    private fun parseProperty(): MutableMap<String, MutableSet<String>> {
        val property = System.getProperty(MODULES_PROPERTY, "")
        if (property.isEmpty()) return mutableMapOf()

        val result = mutableMapOf<String, MutableSet<String>>()
        for (labelEntry in property.split(";")) {
            if (labelEntry.isBlank()) continue
            val parts = labelEntry.split(":", limit = 2)
            if (parts.size == 2) {
                val label = parts[0]
                val modules = parts[1].split(",").filter { it.isNotBlank() }.toMutableSet()
                result[label] = modules
            }
        }
        return result
    }

    // Serialize Map<String, Set<String>> to "label1:mod1,mod2;label2:mod1,mod3"
    private fun serializeProperty(labelMap: Map<String, Set<String>>): String {
        return labelMap.entries.joinToString(";") { (label, modules) ->
            "$label:${modules.joinToString(",")}"
        }
    }

    // Parse "module1=pkg1,pkg2;module2=pkg3" into Map<String, MutableList<String>>
    private fun parseScanPackagesProperty(): MutableMap<String, MutableList<String>> {
        val property = System.getProperty(SCAN_PACKAGES_PROPERTY, "")
        if (property.isEmpty()) return mutableMapOf()

        val result = mutableMapOf<String, MutableList<String>>()
        for (entry in property.split(";")) {
            if (entry.isBlank()) continue
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) {
                val moduleName = parts[0]
                val packages = parts[1].split(",").filter { it.isNotBlank() }.toMutableList()
                result[moduleName] = packages
            }
        }
        return result
    }

    // Serialize Map<String, List<String>> to "module1=pkg1,pkg2;module2=pkg3"
    private fun serializeScanPackages(scanMap: Map<String, List<String>>): String {
        return scanMap.entries.joinToString(";") { (module, packages) ->
            "$module=${packages.joinToString(",")}"
        }
    }
}
