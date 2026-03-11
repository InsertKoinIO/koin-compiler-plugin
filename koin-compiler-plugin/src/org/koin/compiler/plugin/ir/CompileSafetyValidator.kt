package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Orchestrates compile-time safety validation for Koin definitions.
 *
 * Validates that all required dependencies are provided within visible scope:
 * - **A2 (per-module)**: Each module's definitions are validated against
 *   its own definitions + included modules + @Configuration sibling modules.
 *   Visibility is pre-built by [KoinAnnotationProcessor.buildVisibleDefinitions].
 * - **A3 (full-graph)**: All definitions from all modules assembled at startKoin<T>()
 *   are validated together — but only for modules not already validated at A2.
 *
 * The actual matching logic lives in [BindingRegistry]. This class handles the
 * orchestration: deciding what to validate and tracking validated modules.
 */
class CompileSafetyValidator(
    val qualifierExtractor: QualifierExtractor
) {
    private val parameterAnalyzer = ParameterAnalyzer(qualifierExtractor)

    /** FQNames of modules whose definitions were already validated at A2. */
    private val validatedModuleFqNames = mutableSetOf<String>()

    /** All provided type FqNames from the assembled graph (populated by A3 or Phase 3.1). */
    val assembledGraphTypes: Set<String> get() = _assembledGraphTypes
    private val _assembledGraphTypes = mutableSetOf<String>()

    /** Add a type to the assembled graph (used by Phase 3.1 DSL-only validation). */
    fun addAssembledGraphType(fqName: String) { _assembledGraphTypes.add(fqName) }

    /**
     * A2: Validate a module's definitions against all visible definitions.
     *
     * The caller (KoinAnnotationProcessor) has already consolidated the full visibility set
     * (own definitions + includes + @Configuration siblings including cross-module scan hints).
     *
     * @param moduleName Short module name for logging
     * @param moduleFqName Fully qualified module name for tracking
     * @param ownDefinitions Definitions declared in this module (what needs to be validated)
     * @param allVisibleDefinitions All definitions visible to this module (providers for validation)
     */
    fun validate(
        moduleName: String,
        moduleFqName: String?,
        ownDefinitions: List<Definition>,
        allVisibleDefinitions: List<Definition>
    ) {
        KoinPluginLogger.debug { "── A2 Safety: $moduleName ──" }
        KoinPluginLogger.debug { "  own=${ownDefinitions.size}, visible=${allVisibleDefinitions.size}" }
        KoinPluginLogger.debug { "  -> VALIDATING..." }

        val registry = BindingRegistry()
        val errorCount = registry.validateModule(
            moduleName,
            allVisibleDefinitions,
            parameterAnalyzer,
            qualifierExtractor,
            ownDefinitions
        )

        // Mark as validated regardless of errors to prevent duplicate error reporting
        // at A3. If A2 already reported missing dependencies, re-reporting at A3 would
        // produce duplicates since the same error is still present in the full graph.
        if (moduleFqName != null) {
            validatedModuleFqNames.add(moduleFqName)
        }

        if (errorCount > 0) {
            KoinPluginLogger.debug { "  -> DONE: $errorCount errors found" }
        } else {
            KoinPluginLogger.debug { "  -> DONE: all dependencies satisfied" }
        }
    }

    /**
     * A3: Validate the full assembled module graph at the startKoin entry point.
     *
     * Collects ALL definitions from ALL discovered modules and validates that
     * every required dependency is satisfied somewhere in the combined graph.
     * Skips re-validating definitions from modules already validated at A2.
     *
     * @param appName Application class name (for error messages)
     * @param allModuleIrClasses All module IrClasses discovered for this startKoin call
     * @param collectedModuleClasses Local module classes from annotation processing
     * @param getDefinitionsForModule Callback to get definitions for a local module (returns completeness info)
     * @param getDefinitionsForDependencyModule Callback to get definitions from a dependency JAR module
     */
    fun validateFullGraph(
        appName: String,
        allModuleIrClasses: List<IrClass>,
        collectedModuleClasses: List<ModuleClass>,
        getDefinitionsForModule: (ModuleClass) -> DependencyModuleResult,
        getDefinitionsForDependencyModule: (String) -> DependencyModuleResult,
        dslDefinitions: List<Definition> = emptyList()
    ) {
        KoinPluginLogger.debug { "── A3 Safety: Full-graph for $appName ──" }
        KoinPluginLogger.debug { "  modules in graph: ${allModuleIrClasses.size}" }
        KoinPluginLogger.debug { "  already validated at A2: ${validatedModuleFqNames.size} modules" }
        if (validatedModuleFqNames.isNotEmpty()) {
            KoinPluginLogger.debug { "    ${validatedModuleFqNames.joinToString(", ")}" }
        }

        // Collect definitions from all modules in the graph
        // Track which definitions need validation (not already validated at A2)
        val allDefinitions = mutableListOf<Definition>()
        val definitionsToValidate = mutableListOf<Definition>()
        var allModulesComplete = true

        KoinPluginLogger.debug { "  collecting definitions from all modules:" }
        for (moduleIrClass in allModuleIrClasses) {
            val moduleFqName = moduleIrClass.fqNameWhenAvailable?.asString() ?: continue
            val moduleClass = collectedModuleClasses.find {
                it.irClass.fqNameWhenAvailable == moduleIrClass.fqNameWhenAvailable
            }

            val alreadyValidated = moduleFqName in validatedModuleFqNames

            if (moduleClass != null) {
                // Local module — collect all definitions (includes cross-module hints)
                val result = getDefinitionsForModule(moduleClass)
                allDefinitions.addAll(result.definitions)
                if (!result.isComplete) allModulesComplete = false
                val status = if (alreadyValidated) "provider-only (validated at A2)" else "needs validation"
                KoinPluginLogger.debug { "    + $moduleFqName (local): ${result.definitions.size} definitions [$status, complete=${result.isComplete}]" }
                if (!alreadyValidated) {
                    definitionsToValidate.addAll(result.definitions)
                }
            } else {
                // Cross-module @Configuration module from dependency JAR
                KoinPluginLogger.debug { "    + $moduleFqName (dependency JAR):" }
                val result = getDefinitionsForDependencyModule(moduleFqName)
                val status = if (alreadyValidated) "provider-only" else "needs validation"
                KoinPluginLogger.debug { "      -> ${result.definitions.size} definitions [$status, complete=${result.isComplete}]" }
                allDefinitions.addAll(result.definitions)
                if (!result.isComplete) allModulesComplete = false
                if (!alreadyValidated) {
                    definitionsToValidate.addAll(result.definitions)
                }
            }
        }

        // Include DSL definitions as both providers and consumers
        if (dslDefinitions.isNotEmpty()) {
            KoinPluginLogger.debug { "    + DSL definitions: ${dslDefinitions.size}" }
            allDefinitions.addAll(dslDefinitions)
            definitionsToValidate.addAll(dslDefinitions)
        }

        // Store assembled graph types for A4 call-site validation
        for (def in allDefinitions) {
            def.returnTypeClass.fqNameWhenAvailable?.asString()?.let { _assembledGraphTypes.add(it) }
            for (binding in def.bindings) {
                binding.fqNameWhenAvailable?.asString()?.let { _assembledGraphTypes.add(it) }
            }
        }
        KoinPluginLogger.debug { "  assembled graph: ${_assembledGraphTypes.size} provided types" }

        if (!allModulesComplete) {
            KoinPluginLogger.debug { "  -> SKIPPED: some dependency modules have incomplete definitions (hint functions unavailable)" }
            return
        }

        if (allDefinitions.isEmpty()) {
            KoinPluginLogger.debug { "  -> SKIPPED (no definitions found)" }
            return
        }

        if (definitionsToValidate.isEmpty()) {
            KoinPluginLogger.debug { "  -> SKIPPED (all ${allDefinitions.size} definitions already validated at A2)" }
            return
        }

        KoinPluginLogger.debug { "  graph summary: ${definitionsToValidate.size} to validate, ${allDefinitions.size} total providers, from ${allModuleIrClasses.size} modules" }
        KoinPluginLogger.debug { "  -> VALIDATING..." }

        val fullGraphRegistry = BindingRegistry()
        val errorCount = fullGraphRegistry.validateModule(
            "$appName (startKoin)",
            allDefinitions,
            parameterAnalyzer,
            qualifierExtractor,
            definitionsToValidate
        )
        if (errorCount > 0) {
            KoinPluginLogger.debug { "  -> DONE: $errorCount errors found" }
        } else {
            KoinPluginLogger.debug { "  -> DONE: all dependencies satisfied" }
        }
    }
}
