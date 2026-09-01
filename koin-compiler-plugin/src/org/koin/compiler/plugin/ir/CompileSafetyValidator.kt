package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.koin.compiler.plugin.KoinDiagnostic
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Orchestrates compile-time safety validation for Koin definitions.
 *
 * A3 (full-graph) is the SOLE verifier as of 1.1.0: all definitions from all modules assembled at
 * a `startKoin<T>()` / `@KoinApplication` entry point are validated together against the complete
 * closed closure. There is no per-module (A2) pre-pass — a module validated in isolation cannot
 * know how it will actually be wired into a larger app, which made A2's verdicts unsound (a
 * measured false positive: a module failed alone on a dependency provided by a non-dependency
 * peer, and adding only the Gradle dependency edge — no Koin change, same assembled graph — made
 * it pass). See docs/COMPILE_SAFETY_A3_PLAN.md for the full rationale and history.
 *
 * A module with no entry point in this compilation gets NO validation — only generation. The
 * remedy is an entry point; a test with `koinApplication { modules(myModule) }` counts.
 *
 * The actual matching logic lives in [BindingRegistry]. This class handles the orchestration:
 * collecting definitions from the assembled graph and tracking what's already been reported.
 */
class CompileSafetyValidator(
    val qualifierExtractor: QualifierExtractor
) {
    /**
     * Canonicalized cycle keys already reported (KOIN-D004) during this compilation. A definition
     * reachable from more than one entry point would otherwise have its cycle reported once per
     * entry point that reaches it.
     */
    private val reportedCycles = mutableSetOf<String>()

    /**
     * (Definition, parameter, missing type+qualifier) keys already reported as KOIN-D001 during
     * this compilation. Same purpose as [reportedCycles]: a definition reachable from more than
     * one entry point (test-apps has ~9 per compile) gets fully re-validated once per entry
     * point — without this, the same missing dependency would be reported once per root that
     * reaches it.
     */
    private val reportedMissingDeps = mutableSetOf<String>()

    /** All provided type FqNames from the assembled graph (populated by A3 or Phase 3.1). */
    val assembledGraphTypes: Set<String> get() = _assembledGraphTypes
    private val _assembledGraphTypes = mutableSetOf<String>()

    /** Add a type to the assembled graph (used by Phase 3.1 DSL-only validation). */
    fun addAssembledGraphType(fqName: String) { _assembledGraphTypes.add(fqName) }

    /**
     * Validate the full assembled module graph at a `startKoin` / `@KoinApplication` entry point.
     *
     * Collects ALL definitions from ALL discovered modules and validates that every required
     * dependency is satisfied somewhere in the combined graph.
     *
     * @param appName Full display label for error messages, e.g. "MyApp (startKoin)" — the caller
     *   is responsible for the entry-kind suffix (startKoin/koinApplication/koinConfiguration/
     *   withConfiguration); this validator has no EntryKind of its own to pick the right one.
     * @param allModuleIrClasses All module IrClasses discovered for this entry point
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

        // Collect definitions from all modules in the graph — every one needs validation, since
        // there is no A2 pre-pass to have already handled any of them.
        val allDefinitions = mutableListOf<Definition>()
        var allModulesComplete = true

        KoinPluginLogger.debug { "  collecting definitions from all modules:" }
        for (moduleIrClass in allModuleIrClasses) {
            val moduleFqName = moduleIrClass.fqNameWhenAvailable?.asString() ?: continue
            val moduleClass = collectedModuleClasses.find {
                it.irClass.fqNameWhenAvailable == moduleIrClass.fqNameWhenAvailable
            }

            if (moduleClass != null) {
                // Local module — collect all definitions (includes cross-module hints)
                val result = getDefinitionsForModule(moduleClass)
                allDefinitions.addAll(result.definitions)
                if (!result.isComplete) allModulesComplete = false
                KoinPluginLogger.debug { "    + $moduleFqName (local): ${result.definitions.size} definitions [complete=${result.isComplete}]" }
            } else {
                // Cross-module @Configuration module from dependency JAR
                KoinPluginLogger.debug { "    + $moduleFqName (dependency JAR):" }
                val result = getDefinitionsForDependencyModule(moduleFqName)
                KoinPluginLogger.debug { "      -> ${result.definitions.size} definitions [complete=${result.isComplete}]" }
                allDefinitions.addAll(result.definitions)
                if (!result.isComplete) allModulesComplete = false
            }
        }

        // Include DSL definitions as both providers and consumers
        if (dslDefinitions.isNotEmpty()) {
            KoinPluginLogger.debug { "    + DSL definitions: ${dslDefinitions.size}" }
            allDefinitions.addAll(dslDefinitions)
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
            // Incomplete subgraph: at least one module's definitions couldn't be resolved on this
            // compile classpath (e.g. hint functions unavailable). We cannot prove anything missing.
            KoinPluginLogger.debug { "  -> SKIPPED: some dependency modules have incomplete definitions (hint functions unavailable)" }
            return
        }

        if (allDefinitions.isEmpty()) {
            KoinPluginLogger.debug { "  -> SKIPPED (no definitions found)" }
            return
        }

        KoinPluginLogger.debug { "  graph summary: ${allDefinitions.size} total providers/consumers, from ${allModuleIrClasses.size} modules" }
        KoinPluginLogger.debug { "  -> VALIDATING..." }

        val fullGraphRegistry = BindingRegistry()
        val errorCount = fullGraphRegistry.validateModule(
            appName,
            allDefinitions,
            qualifierExtractor,
            reportedCycles = reportedCycles,
            reportedMissingDeps = reportedMissingDeps,
        )
        if (errorCount > 0) {
            KoinPluginLogger.debug { "  -> DONE: $errorCount errors found" }
        } else {
            KoinPluginLogger.debug { "  -> DONE: all dependencies satisfied" }
        }
    }
}
