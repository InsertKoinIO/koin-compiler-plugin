package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.koin.compiler.plugin.KoinDiagnostic
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
    /**
     * FQNames of modules whose definitions were already *authoritatively* validated — either at A2
     * with a complete closure, or against the full assembled graph at A3. A module that produced
     * deferred (unresolved-in-isolation) requirements at A2 is intentionally NOT added here, so A3
     * re-validates it against the sibling modules assembled at the entry point (KTZ-4256 / #51).
     */
    private val validatedModuleFqNames = mutableSetOf<String>()

    /**
     * Binding requirements that A2 could not resolve while validating a module in isolation, held
     * back rather than reported as KOIN-D001. Flushed by [flushDeferred] once all A3 passes have
     * run: still-unresolved deferrals in this compilation become KOIN-W002 warnings (the provider
     * is expected in a sibling module assembled downstream, or a transitive dep off this classpath).
     */
    private val deferredRequirements = mutableListOf<DeferredRequirement>()

    /** Modules re-validated against a complete closed closure at A3 (deferrals for them are settled). */
    private val authoritativelyValidatedFqNames = mutableSetOf<String>()

    /**
     * Oracle answering "does a provider hint for this type exist ANYWHERE on the build graph?"
     * (KTZ-4256 / GH #51). Supplied by [KoinAnnotationProcessor] before A2 runs. When a binding is
     * unresolved in a module's own visibility set, this — NOT closure state — decides the outcome:
     *  - hint exists somewhere → real cross-module dep the local module can't see → DEFER;
     *  - no hint anywhere → genuinely missing → hard KOIN-D001 at A2.
     * Null (unset) means "no provider-hint information", so nothing is treated as cross-module and
     * every unresolved binding is a genuine miss — the pre-KTZ-4256 conservative behavior.
     */
    var providerHintLookup: ((TypeKey) -> Boolean)? = null

    /**
     * Canonicalized cycle keys already reported (KOIN-D004) during this compilation.
     * Same lifecycle as [validatedModuleFqNames] — prevents A2 and A3 from each emitting the
     * same intra-module cycle. Owned here (not in [BindingRegistry]) because the registry is
     * instantiated fresh per validate call.
     */
    private val reportedCycles = mutableSetOf<String>()

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

        // A2 validates a module against its OWN visibility set (own defs + includes + @Configuration
        // siblings). Sibling modules linked only via @KoinApplication(modules=[…]) are not visible
        // here. The discriminator for an unresolved binding is NOT closure state but PROVIDER-HINT
        // EXISTENCE (KTZ-4256 / #51): if some module on the build graph declares a provider hint for
        // the type, it is a real cross-module dep the local module can't see → defer to A3/runtime;
        // otherwise it is genuinely missing → hard KOIN-D001 here, exactly as before the fix. This
        // keeps every single-module typo / @ComponentScan-untyped-entry miss a D001 (no hint anywhere)
        // while #51's sibling dep (which HAS a hint) defers. Cycles / qualifier / property checks fire.
        val deferredBefore = deferredRequirements.size
        val registry = BindingRegistry()
        val errorCount = registry.validateModule(
            moduleName,
            allVisibleDefinitions,
            qualifierExtractor,
            ownDefinitions,
            reportedCycles,
            closureComplete = false,
            deferredSink = deferredRequirements,
            moduleFqName = moduleFqName,
            crossModuleHintLookup = providerHintLookup,
        )
        val deferredHere = deferredRequirements.size - deferredBefore

        // Mark as validated only when nothing was deferred. A module with deferred requirements must
        // be re-validated at A3 against the full assembled graph (siblings included) — that is where
        // a genuinely-missing dependency becomes an authoritative KOIN-D001. Errors that DID fire at
        // A2 (cycles, qualifier mismatch, missing @PropertyValue) are still deduped by marking the
        // module validated in that case, matching the previous behavior.
        if (moduleFqName != null && deferredHere == 0) {
            validatedModuleFqNames.add(moduleFqName)
        }

        if (errorCount > 0) {
            KoinPluginLogger.debug { "  -> DONE: $errorCount errors found${if (deferredHere > 0) ", $deferredHere deferred" else ""}" }
        } else {
            KoinPluginLogger.debug { "  -> DONE: all dependencies satisfied${if (deferredHere > 0) " ($deferredHere deferred to A3/runtime)" else ""}" }
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
        // FQNames of every module assembled into this closure — used to settle A2 deferrals once we
        // confirm the closure is complete (KTZ-4256): their unresolved-in-isolation requirements are
        // now authoritatively validated against the full graph below.
        val modulesInGraph = mutableSetOf<String>()

        KoinPluginLogger.debug { "  collecting definitions from all modules:" }
        for (moduleIrClass in allModuleIrClasses) {
            val moduleFqName = moduleIrClass.fqNameWhenAvailable?.asString() ?: continue
            modulesInGraph.add(moduleFqName)
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
            // Incomplete subgraph: at least one module's definitions couldn't be resolved on this
            // compile classpath (e.g. hint functions unavailable). We cannot prove anything missing,
            // so we do NOT settle A2 deferrals here — they fall through to KOIN-W002 (defer to runtime).
            KoinPluginLogger.debug { "  -> SKIPPED: some dependency modules have incomplete definitions (hint functions unavailable)" }
            return
        }

        // Complete closed closure confirmed: every module assembled here is now authoritatively
        // validated against the full graph, so its A2 deferrals are settled (resolved below, or
        // reported as a genuine KOIN-D001). Record before the empty-set early returns.
        authoritativelyValidatedFqNames.addAll(modulesInGraph)

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
            qualifierExtractor,
            definitionsToValidate,
            reportedCycles,
            closureComplete = true,
        )
        if (errorCount > 0) {
            KoinPluginLogger.debug { "  -> DONE: $errorCount errors found" }
        } else {
            KoinPluginLogger.debug { "  -> DONE: all dependencies satisfied" }
        }
    }

    /**
     * Flush A2 deferrals after all A3 passes have run (called once from Phase 3.7, KTZ-4256).
     *
     * A deferred requirement is settled — and therefore silent — when either:
     *  - its module was authoritatively re-validated at A3 against a complete closed closure
     *    ([authoritativelyValidatedFqNames]): a genuine miss already surfaced as KOIN-D001 there; or
     *  - its type is present in [assembledGraphTypes]: a sibling in this compilation provides it.
     *
     * Everything else is a binding this compilation cannot resolve and cannot prove missing — a leaf
     * module built without an entry point, or an incomplete subgraph. Those become KOIN-W002 warnings
     * (validated later at the application `@KoinApplication` / at runtime), never a hard error. Deduped
     * by (module, def, param, type) so the same requirement isn't warned about twice.
     */
    fun flushDeferred() {
        if (deferredRequirements.isEmpty()) return
        KoinPluginLogger.debug { "── Phase 3.7: flushing ${deferredRequirements.size} deferred requirement(s) ──" }
        val warned = mutableSetOf<String>()
        var emitted = 0
        for (d in deferredRequirements) {
            if (d.moduleFqName != null && d.moduleFqName in authoritativelyValidatedFqNames) continue
            val typeFqName = d.requirement.typeKey.fqName?.asString()
                ?: d.requirement.typeKey.classId?.asFqNameString()
            if (typeFqName != null && typeFqName in assembledGraphTypes) continue
            val dedupKey = "${d.moduleFqName ?: d.moduleName}|${d.defName}|${d.requirement.paramName}|${typeFqName ?: d.requirement.typeKey.render()}"
            if (!warned.add(dedupKey)) continue
            KoinPluginLogger.report(
                KoinDiagnostic.DeferredMissingBinding(
                    type = d.requirement.typeKey.render(),
                    qualifier = d.qualifierDisplay,
                    def = d.defName,
                    param = d.requirement.paramName,
                    module = d.moduleName,
                )
            )
            emitted++
        }
        KoinPluginLogger.debug { "  -> Phase 3.7: emitted $emitted KOIN-W002 warning(s)" }
    }
}
