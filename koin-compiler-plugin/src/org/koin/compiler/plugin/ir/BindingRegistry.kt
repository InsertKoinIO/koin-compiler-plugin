package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.koin.compiler.plugin.KoinDiagnostic
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.ProvidedTypeRegistry
import org.koin.compiler.plugin.PropertyValueRegistry

/**
 * Identifies a provided type in the DI container.
 *
 * @param classId The ClassId of the type (for serializable cross-module comparisons)
 * @param fqName The FqName (for display in error messages)
 */
data class TypeKey(
    val classId: ClassId?,
    val fqName: FqName?
) {
    fun render(): String = fqName?.asString() ?: classId?.asFqNameString() ?: "<unknown>"
}

/**
 * A dependency requirement from a constructor/function parameter.
 */
data class Requirement(
    val typeKey: TypeKey,
    val paramName: String,
    val isNullable: Boolean,
    val hasDefault: Boolean,
    val isInjectedParam: Boolean,
    val isProvided: Boolean,
    val isScopeId: Boolean,
    val scopeIdName: String?,
    val isLazy: Boolean,
    val isList: Boolean,
    val isProperty: Boolean,
    val propertyKey: String?,
    val qualifier: QualifierValue?
) {
    /**
     * Whether this requirement must be validated (must have a matching provider).
     * Returns false for requirements that are safe without a provider.
     */
    fun requiresValidation(): Boolean {
        if (isInjectedParam) return false  // Provided at runtime via parametersOf()
        if (isProvided) return false       // @Provided — externally available at runtime
        if (isScopeId) return false        // @ScopeId — resolved from named scope at runtime
        if (isNullable) return false        // getOrNull() handles missing
        if (isList) return false            // getAll() returns empty if none
        if (isProperty) return false        // Property injection (validated separately)

        // If skipDefaultValues is enabled and param has a default, skip
        if (KoinPluginLogger.skipDefaultValuesEnabled && hasDefault && qualifier == null) return false

        return true
    }
}

/**
 * Description of a single `@InjectedParam` slot on a definition, used for call-site
 * `parametersOf(...)` validation (KOIN-D005/D006).
 *
 * Captured locally at definition collection AND reconstructed cross-module from the
 * `injectedparams_*` hint function signature — both produce the same shape.
 *
 * @property name the parameter name as declared on the constructor (used in diagnostic messages)
 * @property typeFqName the parameter's classifier FqName (raw; generics are erased to match
 *           Koin's runtime resolution model, see [HintTypeErasure])
 * @property isNullable whether the parameter type is marked nullable
 */
data class InjectedParamSlot(
    val name: String,
    val typeFqName: String,
    val isNullable: Boolean,
)

/**
 * An unresolved binding requirement that was NOT reported as a hard error because it was found while
 * validating an *open* (per-module / leaf) closure rather than a *complete closed* closure. Carried
 * back to the orchestrator ([CompileSafetyValidator]) which decides whether it becomes a KOIN-D001
 * error (re-validated against the full assembled graph at `@KoinApplication`) or a KOIN-W002 warning
 * (no complete closure in this compilation — defer to runtime). See KTZ-4256 / GH #51.
 *
 * @property moduleFqName FQName of the module whose isolated validation produced the deferral (null
 *   when the module has no resolvable FqName). Used to skip re-emitting once A3 authoritatively
 *   validates the same module.
 */
data class DeferredRequirement(
    val defName: String,
    val moduleName: String,
    val moduleFqName: String?,
    val requirement: Requirement,
    val qualifierDisplay: String?,
)

/**
 * Registry of all provided bindings, with per-module validation.
 *
 * Collects all definitions during annotation processing Phase 1,
 * then validates that each module's definitions can satisfy each other's requirements.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class BindingRegistry {

    companion object {
        /**
         * Framework types that are always available at runtime (provided by the platform, not DI).
         * These are skipped during validation to avoid false positives.
         */
        private val WHITELISTED_TYPES = setOf(
            // Android core
            "android.content.Context",
            "android.app.Activity",
            "android.app.Application",
            // AndroidX - scope-provided components
            "androidx.activity.ComponentActivity",
            "androidx.fragment.app.Fragment",
            "androidx.lifecycle.SavedStateHandle",
            "androidx.work.WorkerParameters",
        )

        fun isWhitelistedType(fqName: String): Boolean = fqName in WHITELISTED_TYPES

        /**
         * Pure-graph DFS cycle detector. Generic on node type so it can be unit-tested with
         * `String` keys without standing up IR. Returns each detected cycle as a closed path
         * `[A, ..., A]` in DFS-discovery order.
         *
         * Iterative DFS with three-color marking: WHITE (unseen), GRAY (on current DFS stack),
         * BLACK (fully explored). A back-edge `node -> next` where `next` is GRAY closes a
         * cycle; we reconstruct the path by walking the [parent] map from `node` up to `next`.
         *
         * One cycle is reported per back-edge discovered. Callers that want one report per
         * topologically distinct cycle should canonicalize and dedup (see [canonicalizeCycle]).
         */
        fun <N> findCyclesInGraph(nodes: Iterable<N>, adjacency: Map<N, List<N>>): List<List<N>> {
            val gray = 1
            val black = 2
            val color = HashMap<N, Int>()
            val parent = HashMap<N, N>()
            val results = mutableListOf<List<N>>()

            for (root in nodes) {
                if (color[root] != null) continue
                val stack = ArrayDeque<Pair<N, Iterator<N>>>()
                color[root] = gray
                stack.addLast(root to (adjacency[root] ?: emptyList()).iterator())

                while (stack.isNotEmpty()) {
                    val (node, it) = stack.last()
                    if (!it.hasNext()) {
                        color[node] = black
                        stack.removeLast()
                        continue
                    }
                    val next = it.next()
                    when (color[next]) {
                        null -> {
                            color[next] = gray
                            parent[next] = node
                            stack.addLast(next to (adjacency[next] ?: emptyList()).iterator())
                        }
                        gray -> {
                            val path = mutableListOf<N>()
                            var cur: N? = node
                            while (cur != null && cur != next) {
                                path.add(cur)
                                cur = parent[cur]
                            }
                            if (cur == next) {
                                path.add(next)
                                path.reverse()
                                results.add(path + next)
                            }
                        }
                        black -> { /* fully explored — no new cycle via this edge */ }
                    }
                }
            }
            return results
        }

        /**
         * Canonicalize a closed cycle `[A, B, C, A]` to a stable string by dropping the trailing
         * duplicate and rotating to start at the lexicographically smallest node. So
         * `[A, B, C, A]`, `[B, C, A, B]`, and `[C, A, B, C]` all produce `"A→B→C"`.
         */
        fun canonicalizeCycle(cycle: List<String>): String {
            if (cycle.size <= 1) return cycle.joinToString("→")
            val open = cycle.dropLast(1) // strip trailing duplicate
            val minIdx = open.indices.minByOrNull { open[it] } ?: 0
            val rotated = open.drop(minIdx) + open.take(minIdx)
            return rotated.joinToString("→")
        }

        // ────────────────────────────────────────────────────────────────────────────
        // @InjectedParam call-site shape validation (KOIN-D005)
        // ────────────────────────────────────────────────────────────────────────────

        /**
         * A single positional argument captured from `parametersOf(arg0, arg1, …)` at a call site.
         * `typeFqName == null` means the IR could not be classified (lambda was non-trivial or
         * the arg classifier was missing) — caller should treat the whole call site as ambiguous
         * and SKIP validation rather than reporting a spurious mismatch.
         */
        data class ParametersOfArg(
            val typeFqName: String?,
            val isNullable: Boolean,
        )

        /** Result of [validateInjectedParamShape]. */
        sealed class ShapeCheck {
            object Ok : ShapeCheck()

            /** parametersOf args couldn't be classified — call site is ambiguous, skip reporting. */
            object Ambiguous : ShapeCheck()

            data class ArityMismatch(val expected: Int, val actual: Int) : ShapeCheck()

            /** First positional index that doesn't type-match; [expected]/[actual] are the slot lists. */
            data class TypeMismatch(
                val index: Int,
                val expectedSlot: InjectedParamSlot,
                val actualArg: ParametersOfArg,
            ) : ShapeCheck()
        }

        /**
         * Validate a `parametersOf(...)` shape against the target definition's `@InjectedParam` slots.
         *
         * Rules (intentionally strict to minimise false positives — see plan for KOIN-D005):
         *  - Arity must match exactly. Extra args and missing args are both ERROR.
         *  - Type match: raw FqName equality. Generics are erased (matches Koin runtime + the hint
         *    type-erasure convention used everywhere else in the plugin).
         *  - Nullability: a `null`-typed arg (typeFqName=null with isNullable=true) is always valid;
         *    a non-null arg into a nullable slot is allowed; a nullable arg into a non-null slot
         *    is rejected as a type mismatch.
         *  - Wildcards: an arg whose `typeFqName == null && isNullable == false` means
         *    "couldn't classify" — the whole call is treated as [ShapeCheck.Ambiguous] and skipped.
         *
         * Subtype-aware matching (e.g. `parametersOf(SubFoo())` against a `Foo` slot) is a planned
         * follow-up — pure-data shape check has no view of subtype relations.
         */
        fun validateInjectedParamShape(
            slots: List<InjectedParamSlot>,
            args: List<ParametersOfArg>,
        ): ShapeCheck {
            // If any arg is "couldn't classify" we don't have enough info to compare — skip.
            if (args.any { it.typeFqName == null && !it.isNullable }) return ShapeCheck.Ambiguous

            if (args.size != slots.size) return ShapeCheck.ArityMismatch(slots.size, args.size)

            for (i in slots.indices) {
                val slot = slots[i]
                val arg = args[i]
                // null literal arg: only valid into nullable slot
                if (arg.typeFqName == null && arg.isNullable) {
                    if (!slot.isNullable) return ShapeCheck.TypeMismatch(i, slot, arg)
                    continue
                }
                // Type names must match
                if (arg.typeFqName != slot.typeFqName) {
                    return ShapeCheck.TypeMismatch(i, slot, arg)
                }
                // Non-null arg into non-null slot OK; nullable arg into non-null slot is an error.
                if (arg.isNullable && !slot.isNullable) {
                    return ShapeCheck.TypeMismatch(i, slot, arg)
                }
            }
            return ShapeCheck.Ok
        }

        /** Pretty-render a slot list for diagnostic messages. */
        fun renderSlots(slots: List<InjectedParamSlot>): List<String> =
            slots.map { "${it.name}: ${it.typeFqName}${if (it.isNullable) "?" else ""}" }

        /** Pretty-render an args list for diagnostic messages. */
        fun renderArgs(args: List<ParametersOfArg>): List<String> =
            args.map {
                val type = it.typeFqName ?: "<unknown>"
                "$type${if (it.isNullable) "?" else ""}"
            }
    }

    /**
     * Validate a module's definitions: check that all required dependencies are provided
     * within the set of definitions visible to this module.
     *
     * @param moduleName Name of the module (for error messages)
     * @param definitions All definitions collected for this module (used to build provided types)
     * @param qualifierExtractor Extractor for reading qualifier annotations from definitions
     * @param definitionsToValidate Subset of definitions whose requirements should be checked.
     *   If null, all definitions are validated. Use this to skip re-validating definitions
     *   that were already checked at A2 while still including them as providers.
     * @param closureComplete Whether the [definitions] set is the *complete closed* closure assembled
     *   at a `@KoinApplication` / `startKoin` entry point (A3). When true the graph is authoritative,
     *   so an unresolved binding is always a genuine missing dependency → KOIN-D001 ERROR and deferral
     *   never happens. When false (A2, per-module), an unresolved binding is deferred ONLY if a
     *   [crossModuleHintLookup] proves a provider exists elsewhere on the build graph (KTZ-4256 / #51);
     *   with no such hint it is still a hard KOIN-D001 here.
     * @param deferredSink Collector for deferred (cross-module) unresolved requirements. Deferral only
     *   fires when a [crossModuleHintLookup] is supplied AND it reports a provider hint for the type.
     * @param moduleFqName FQName of the module being validated, carried into deferrals.
     * @param crossModuleHintLookup Oracle "does a provider hint for this type exist ANYWHERE on the
     *   build graph?" (KTZ-4256 / #51). When an unresolved binding's type IS provided by a hint
     *   somewhere, it is a real cross-module dep the local module can't see → recorded in [deferredSink]
     *   instead of erroring (settled at A3 / KOIN-W002). When it is NOT (or the lookup is null), the
     *   binding is genuinely missing → hard KOIN-D001. Ignored — no deferral — when [closureComplete]
     *   is true at A3 (the assembled graph is already authoritative).
     * @return Number of errors found (deferred requirements are NOT counted as errors)
     */
    fun validateModule(
        moduleName: String,
        definitions: List<Definition>,
        qualifierExtractor: QualifierExtractor,
        definitionsToValidate: List<Definition>? = null,
        reportedCycles: MutableSet<String>? = null,
        closureComplete: Boolean = true,
        deferredSink: MutableList<DeferredRequirement>? = null,
        moduleFqName: String? = null,
        crossModuleHintLookup: ((TypeKey) -> Boolean)? = null,
    ): Int {
        // Build the set of provided types from ALL definitions
        val providedTypes = mutableSetOf<ProviderKey>()

        for (def in definitions) {
            val typeKey = typeKeyFromDefinition(def)
            val qualifier = extractQualifierFromDefinition(def, qualifierExtractor)
            val scopeClass = def.scopeClass

            // The definition provides its own type
            providedTypes.add(ProviderKey(typeKey, qualifier, scopeClass))
            val scopeStr = scopeClass?.fqNameWhenAvailable?.asString()?.let { " (scope=$it)" } ?: ""
            val qualifierStr = when (qualifier) {
                is QualifierValue.StringQualifier -> " @Named(\"${qualifier.name}\")"
                is QualifierValue.TypeQualifier -> " @Qualifier(${qualifier.irClass.name}::class)"
                null -> ""
            }
            KoinPluginLogger.debug { "    provides: ${typeKey.render()}$qualifierStr$scopeStr" }

            // It also provides its bound interfaces
            for (binding in def.bindings) {
                val bindingTypeKey = TypeKey(
                    classId = ParameterAnalyzer.classIdFromIrClass(binding),
                    fqName = binding.fqNameWhenAvailable
                )
                providedTypes.add(ProviderKey(bindingTypeKey, qualifier, scopeClass))
                KoinPluginLogger.debug { "    provides (binding): ${bindingTypeKey.render()}$qualifierStr$scopeStr" }
            }
        }

        // Bare (qualifier/scope-agnostic) FQNames of every type provided in THIS module's visibility
        // set. Used by the KTZ-4256 gate to tell a cross-module gap apart from a same-module
        // qualifier/scope mismatch: if the type is already visible here, an unresolved requirement is
        // a genuine LOCAL miss (wrong @Named / wrong @Scope) → hard D001, never a cross-module defer.
        val locallyProvidedTypeFqNames = providedTypes.mapNotNullTo(hashSetOf()) {
            it.typeKey.fqName?.asString() ?: it.typeKey.classId?.asFqNameString()
        }

        // Only validate requirements from the specified subset (or all if not specified)
        val toValidate = definitionsToValidate ?: definitions

        KoinPluginLogger.debug { "  provided types registry: ${providedTypes.size} entries" }
        KoinPluginLogger.debug { "  definitions to check: ${toValidate.size}/${definitions.size}" }

        // Validate each definition's requirements
        var errorCount = 0
        for (def in toValidate) {
            val requirements = extractRequirements(def)
            val defName = definitionDisplayName(def)
            val defScopeClass = def.scopeClass
            KoinPluginLogger.debug {
                val o = def.origin
                val originStr = if (o != null) " [${o.moduleFqName ?: "?"}${o.filePath?.let { " ${it.substringAfterLast('/')}:${o.line ?: "?"}" } ?: ""}]" else ""
                val reqStr = requirements.joinToString(", ") { r -> r.typeKey.render() + if (r.requiresValidation()) "" else "(skip)" }
                val note = when {
                    def !is Definition.ExternalFunctionDef -> ""
                    // A3 Gate-3: an external provider now carries its requirements via the funcreqs
                    // hint; empty still means provider-only (nothing carried, or a qualified req cut-1
                    // can't encode yet).
                    requirements.isEmpty() -> " (provider-only; no cross-module reqs carried)"
                    else -> " (cross-module reqs carried via funcreqs)"
                }
                "    validating: $defName$originStr — ${requirements.size} req(s): [$reqStr]$note"
            }

            for (req in requirements) {
                if (!req.requiresValidation()) {
                    val reason = when {
                        req.isInjectedParam -> "@InjectedParam"
                        req.isProvided -> "@Provided"
                        req.isScopeId -> "@ScopeId(\"${req.scopeIdName}\")"
                        req.isNullable -> "nullable"
                        req.isList -> "List (getAll)"
                        req.isProperty -> "@Property"
                        KoinPluginLogger.skipDefaultValuesEnabled && req.hasDefault && req.qualifier == null -> "hasDefault (skipDefaultValues)"
                        else -> "unknown"
                    }
                    KoinPluginLogger.debug { "      skip '${req.paramName}': ${req.typeKey.render()} ($reason)" }

                    // Validate @Property/@PropertyValue matching inline (no second pass)
                    if (req.isProperty && req.propertyKey != null && !PropertyValueRegistry.hasDefault(req.propertyKey)) {
                        KoinPluginLogger.report(
                            KoinDiagnostic.MissingPropertyValue(
                                key = req.propertyKey,
                                def = defName,
                                module = moduleName,
                            )
                        )
                    }

                    continue
                }

                // Skip @Provided types and framework-provided types (always available at runtime)
                val reqFqName = req.typeKey.fqName?.asString() ?: req.typeKey.classId?.asFqNameString()
                if (reqFqName != null && ProvidedTypeRegistry.isProvided(reqFqName)) {
                    KoinPluginLogger.debug { "      skip '${req.paramName}': ${req.typeKey.render()} (@Provided)" }
                    continue
                }
                if (reqFqName != null && isWhitelistedType(reqFqName)) {
                    KoinPluginLogger.debug { "      skip '${req.paramName}': ${req.typeKey.render()} (framework whitelist)" }
                    continue
                }

                // Look for a matching provider
                val found = findProvider(req, providedTypes, defScopeClass)
                // KTZ-4256 / #51: at A2 (open closure), defer ONLY when a provider hint for this type
                // exists somewhere on the build graph — i.e. it is a real cross-module dep the local
                // module can't see. With no hint anywhere, the type is genuinely missing and must stay
                // a hard KOIN-D001 (single-module typo, @ComponentScan untyped-entry, etc.). At A3
                // (closureComplete) the assembled graph is authoritative, so never defer.
                val reqTypeFqName = req.typeKey.fqName?.asString() ?: req.typeKey.classId?.asFqNameString()
                val visibleLocally = reqTypeFqName != null && reqTypeFqName in locallyProvidedTypeFqNames
                val hasCrossModuleHint = !closureComplete &&
                    deferredSink != null &&
                    !visibleLocally &&
                    crossModuleHintLookup?.invoke(req.typeKey) == true
                if (found) {
                    KoinPluginLogger.debug { "      OK '${req.paramName}': ${req.typeKey.render()}" }
                } else if (hasCrossModuleHint) {
                    // Provider hint exists in a sibling / dependency module not visible here — defer to
                    // the complete closed closure at @KoinApplication (KOIN-D001) or to runtime (KOIN-W002).
                    KoinPluginLogger.debug { "      DEFERRED '${req.paramName}': ${req.typeKey.render()} (cross-module provider hint exists)" }
                    deferredSink?.add(
                        DeferredRequirement(
                            defName = defName,
                            moduleName = moduleName,
                            moduleFqName = moduleFqName,
                            requirement = req,
                            qualifierDisplay = qualifierDisplay(req.qualifier),
                        )
                    )
                } else {
                    // No provider hint anywhere on the graph → genuinely missing → authoritative D001.
                    KoinPluginLogger.debug { "      MISSING '${req.paramName}': ${req.typeKey.render()}  [culprit ${def.origin?.let { "${it.filePath?.substringAfterLast('/') ?: it.moduleFqName ?: "?"}:${it.line ?: "?"}" } ?: definitionDisplayName(def)}]" }
                    // Attribute to the definition's OWN module when we know it. For a DSL definition
                    // that is its `module { }` val (modulePropertyId) — far more useful than the generic
                    // "DSL graph" validation-context label, especially in a multi-module app. Other def
                    // kinds already carry a meaningful moduleName (the @Module class / entry point).
                    val owningModule = (def as? Definition.DslDef)?.modulePropertyId ?: moduleName
                    reportMissingDependency(req, defName, owningModule, providedTypes)
                    errorCount++
                }
            }
        }

        // Cycle detection runs over the full provider set (not just toValidate) so a back-edge
        // through an already-validated definition still surfaces. Dedup happens via [reportedCycles]
        // so the same cycle isn't reported at both A2 and A3.
        val cycleErrors = detectCycles(definitions, qualifierExtractor, reportedCycles)
        errorCount += cycleErrors

        if (errorCount == 0) {
            KoinPluginLogger.debug { "  result: OK - all dependencies satisfied for $moduleName" }
        } else {
            KoinPluginLogger.debug { "  result: FAILED - $errorCount missing dependencies in $moduleName" }
        }

        return errorCount
    }

    /**
     * Detect constructor-injection cycles in the assembled graph and report KOIN-D004 per cycle.
     *
     * Nodes are each definition's "primary" ProviderKey (typeKey of its own return type + qualifier
     * + scope). Bindings (interface ProviderKeys) collapse to their owning definition's primary key,
     * so two providers sharing an interface don't appear as separate nodes.
     *
     * Edges come from constructor/function parameters whose requirement resolves to another
     * provider. Non-edges (do not contribute to cycles):
     *  - `Lazy<T>` — canonical runtime cycle breaker
     *  - `@InjectedParam`, `@Provided`, `@ScopeId` — not constructor-time DI edges
     *  - nullable / `List<T>` / `@Property` / default-valued — already non-fatal at runtime
     *  - `@Provided` types and framework-whitelisted types
     *
     * Algorithm: iterative DFS with three-color marking. On a back-edge to a GRAY ancestor,
     * walk the parent chain to reconstruct the cycle path, canonicalize (rotate to start at the
     * lexicographically smallest node) and dedup via [reportedCycles].
     *
     * @return number of NEW cycles reported (after dedup).
     */
    private fun detectCycles(
        definitions: List<Definition>,
        qualifierExtractor: QualifierExtractor,
        reportedCycles: MutableSet<String>?,
    ): Int {
        if (definitions.isEmpty()) return 0

        // primary key (own type) -> definition; binding keys -> primary (so a binding requirement
        // routes to the owning definition).
        val primaryToDef = mutableMapOf<ProviderKey, Definition>()
        val keyToPrimary = mutableMapOf<ProviderKey, ProviderKey>()

        for (def in definitions) {
            val typeKey = typeKeyFromDefinition(def)
            val qualifier = extractQualifierFromDefinition(def, qualifierExtractor)
            val scopeClass = def.scopeClass
            val primary = ProviderKey(typeKey, qualifier, scopeClass)
            if (primaryToDef.putIfAbsent(primary, def) == null) {
                keyToPrimary[primary] = primary
                for (binding in def.bindings) {
                    val bindingKey = ProviderKey(
                        TypeKey(
                            classId = ParameterAnalyzer.classIdFromIrClass(binding),
                            fqName = binding.fqNameWhenAvailable,
                        ),
                        qualifier,
                        scopeClass,
                    )
                    keyToPrimary.putIfAbsent(bindingKey, primary)
                }
            }
        }

        if (primaryToDef.size < 1) return 0

        // Adjacency: primary -> set of primary keys reachable in one step.
        val allKeys = keyToPrimary.keys
        val adj = HashMap<ProviderKey, List<ProviderKey>>(primaryToDef.size)
        for ((primary, def) in primaryToDef) {
            val edges = LinkedHashSet<ProviderKey>()
            for (req in extractRequirements(def)) {
                if (!req.requiresValidation()) continue
                if (req.isLazy) continue
                val reqFqName = req.typeKey.fqName?.asString() ?: req.typeKey.classId?.asFqNameString()
                if (reqFqName != null && ProvidedTypeRegistry.isProvided(reqFqName)) continue
                if (reqFqName != null && isWhitelistedType(reqFqName)) continue
                val matchedKey = findMatchingProvider(req, allKeys, def.scopeClass) ?: continue
                val target = keyToPrimary[matchedKey] ?: continue
                edges += target
            }
            adj[primary] = edges.toList()
        }

        val cycles = findCyclesInGraph(primaryToDef.keys, adj)
        var newCycles = 0
        for (cycle in cycles) {
            val rendered = cycle.map { key ->
                primaryToDef[key]?.let { definitionDisplayName(it) } ?: key.typeKey.render()
            }
            val canonical = canonicalizeCycle(rendered)
            if (reportedCycles == null || reportedCycles.add(canonical)) {
                KoinPluginLogger.report(KoinDiagnostic.CircularDependency(rendered))
                newCycles++
            }
        }

        if (newCycles > 0) {
            KoinPluginLogger.debug { "  cycle detection: $newCycles new cycle(s) reported" }
        }
        return newCycles
    }


    /**
     * Search for a provider matching the requirement.
     * Checks both same-scope and root-scope providers.
     */
    private fun findProvider(
        req: Requirement,
        providedTypes: Set<ProviderKey>,
        consumerScopeClass: IrClass?
    ): Boolean {
        val reqFqName = req.typeKey.fqName
        val reqClassId = req.typeKey.classId

        for (provider in providedTypes) {
            // Type must match (by FqName or ClassId)
            val typeMatch = when {
                reqFqName != null && provider.typeKey.fqName != null -> reqFqName == provider.typeKey.fqName
                reqClassId != null && provider.typeKey.classId != null -> reqClassId == provider.typeKey.classId
                else -> false
            }
            if (!typeMatch) continue

            // Qualifier must match
            if (!qualifiersMatch(req.qualifier, provider.qualifier)) {
                KoinPluginLogger.debug { "        type match ${req.typeKey.render()} but qualifier mismatch: required=${req.qualifier?.debugString()} vs provided=${provider.qualifier?.debugString()}" }
                continue
            }

            // Scope visibility: root-scope providers are visible everywhere,
            // same-scope providers are visible within the scope
            val providerScope = provider.scopeClass
            if (providerScope == null) {
                // Root scope — visible to all
                return true
            }
            if (consumerScopeClass != null && providerScope.fqNameWhenAvailable == consumerScopeClass.fqNameWhenAvailable) {
                // Same scope
                return true
            }
            // Different scope — not visible, keep searching
            KoinPluginLogger.debug { "        type match ${req.typeKey.render()} but scope mismatch: consumer=${consumerScopeClass?.fqNameWhenAvailable} vs provider=${providerScope.fqNameWhenAvailable}" }
        }

        return false
    }

    /**
     * Search for a provider matching the requirement and return the matched [ProviderKey], or
     * `null` if none matches. Used by cycle detection to map a requirement to its resolving node
     * in the graph. Mirrors [findProvider] but without debug logging (cycle scan walks every
     * edge — extra spam would drown the build log).
     */
    private fun findMatchingProvider(
        req: Requirement,
        providedTypes: Set<ProviderKey>,
        consumerScopeClass: IrClass?,
    ): ProviderKey? {
        val reqFqName = req.typeKey.fqName
        val reqClassId = req.typeKey.classId

        for (provider in providedTypes) {
            val typeMatch = when {
                reqFqName != null && provider.typeKey.fqName != null -> reqFqName == provider.typeKey.fqName
                reqClassId != null && provider.typeKey.classId != null -> reqClassId == provider.typeKey.classId
                else -> false
            }
            if (!typeMatch) continue
            if (!qualifiersMatch(req.qualifier, provider.qualifier)) continue
            val providerScope = provider.scopeClass
            if (providerScope == null) return provider
            if (consumerScopeClass != null && providerScope.fqNameWhenAvailable == consumerScopeClass.fqNameWhenAvailable) {
                return provider
            }
        }
        return null
    }

    private fun qualifiersMatch(required: QualifierValue?, provided: QualifierValue?): Boolean {
        if (required == null && provided == null) return true
        if (required == null || provided == null) return false
        return when {
            required is QualifierValue.StringQualifier && provided is QualifierValue.StringQualifier ->
                required.name == provided.name
            required is QualifierValue.TypeQualifier && provided is QualifierValue.TypeQualifier ->
                required.irClass.fqNameWhenAvailable == provided.irClass.fqNameWhenAvailable
            else -> false
        }
    }

    private fun reportMissingDependency(
        req: Requirement,
        defName: String,
        moduleName: String,
        providedTypes: Set<ProviderKey>
    ) {
        val typeName = req.typeKey.render()
        val qualifierStr = qualifierDisplay(req.qualifier)

        // Hint: find similar bindings (same type, different qualifier)
        val similarBindings = providedTypes.filter { provider ->
            val typeMatch = when {
                req.typeKey.fqName != null && provider.typeKey.fqName != null ->
                    req.typeKey.fqName == provider.typeKey.fqName
                req.typeKey.classId != null && provider.typeKey.classId != null ->
                    req.typeKey.classId == provider.typeKey.classId
                else -> false
            }
            typeMatch && !qualifiersMatch(req.qualifier, provider.qualifier)
        }
        val hint: String? = if (similarBindings.isNotEmpty()) {
            buildString {
                append("Found similar binding: $typeName")
                when (val q = similarBindings.first().qualifier) {
                    is QualifierValue.StringQualifier -> append(" with qualifier @Named(\"${q.name}\")")
                    is QualifierValue.TypeQualifier -> append(" with qualifier @Qualifier(${q.irClass.name}::class)")
                    null -> append(" (no qualifier)")
                }
            }
        } else null

        KoinPluginLogger.report(
            KoinDiagnostic.MissingBinding(
                type = typeName,
                qualifier = qualifierStr,
                def = defName,
                param = req.paramName,
                module = moduleName,
                hint = hint,
            )
        )
    }

    // ================================================================================
    // Helpers
    // ================================================================================

    /** Render a qualifier for diagnostic messages, or null when unqualified. */
    private fun qualifierDisplay(qualifier: QualifierValue?): String? = when (qualifier) {
        is QualifierValue.StringQualifier -> "@Named(\"${qualifier.name}\")"
        is QualifierValue.TypeQualifier -> "@Qualifier(${qualifier.irClass.name}::class)"
        null -> null
    }

    private fun typeKeyFromDefinition(def: Definition): TypeKey {
        val irClass = def.returnTypeClass
        return TypeKey(
            classId = ParameterAnalyzer.classIdFromIrClass(irClass),
            fqName = irClass.fqNameWhenAvailable
        )
    }

    private fun extractQualifierFromDefinition(def: Definition, qualifierExtractor: QualifierExtractor): QualifierValue? {
        // Extract qualifier from the IR element (class or function) using the shared extractor.
        return when (def) {
            is Definition.ClassDef -> def.qualifier ?: qualifierExtractor.extractFromClass(def.irClass)
            is Definition.FunctionDef -> qualifierExtractor.extractFromDeclaration(def.irFunction)
            is Definition.TopLevelFunctionDef -> qualifierExtractor.extractFromDeclaration(def.irFunction)
            is Definition.DslDef -> def.qualifier ?: qualifierExtractor.extractFromClass(def.irClass)
            is Definition.ExternalFunctionDef -> def.qualifier
        }
    }

    // The verifier consumes the model: requirements are attached at collection time (the metadata
    // contract, A3 §2) — a single source of truth, no re-derivation and no classifier drift.
    // ExternalFunctionDef carries an empty list (provider-only; the Gate-2 carrier fills it).
    //
    // A whole-suite differential against on-demand re-derivation reported 0 mismatches, but that
    // was a BLIND SPOT, not a proof: the suite had no `bind` + missing-dependency case to differ on.
    // `single<X>() bind Y::class` rebuilds its definition via copy(), which resets the body-held
    // requirements, and this bare field read has no fallback — so BOTH consumers below went silent
    // (KOIN-D001 at :376 and KOIN-D004 cycle detection at :550). Fixed by Definition.retainA3Metadata;
    // covered by testData/diagnostics/dsl_bind_missing_dependency_d001.kt and its variants. Treat an
    // unpopulated `requirements` as a bug at the collection site, never as "no requirements".
    private fun extractRequirements(def: Definition): List<Requirement> = def.requirements

    private fun definitionDisplayName(def: Definition): String {
        return when (def) {
            is Definition.ClassDef -> def.irClass.fqNameWhenAvailable?.asString() ?: def.irClass.name.asString()
            is Definition.FunctionDef -> "${def.moduleInstance.name}.${def.irFunction.name}()"
            is Definition.TopLevelFunctionDef -> def.irFunction.fqNameWhenAvailable?.asString()
                ?: def.irFunction.name.asString()
            is Definition.DslDef -> "dsl:${def.irClass.fqNameWhenAvailable?.asString() ?: def.irClass.name.asString()}"
            is Definition.ExternalFunctionDef -> def.returnTypeClass.fqNameWhenAvailable?.asString()
                ?: def.returnTypeClass.name.asString()
        }
    }

    /**
     * Key for tracking what's provided.
     */
    internal data class ProviderKey(
        val typeKey: TypeKey,
        val qualifier: QualifierValue?,
        val scopeClass: IrClass?
    ) {
        /** Scope FqName for comparison (null = root scope). */
        val scopeFqName: String? get() = scopeClass?.fqNameWhenAvailable?.asString()
    }

    // ================================================================================
    // Unit-testable validation (no IR dependencies)
    // ================================================================================

    /**
     * Validate requirements against a provided set using only data types.
     * Used by unit tests to verify matching logic without IR.
     *
     * @param requirements List of (defName, scopeFqName, requirement) triples
     * @param provided Set of (TypeKey, qualifier, scopeFqName) triples representing providers
     * @param moduleName For error messages
     * @return List of (defName, requirement) pairs that are missing
     */
    fun validateRequirementsData(
        requirements: List<Triple<String, String?, Requirement>>,
        provided: Set<Triple<TypeKey, QualifierValue?, String?>>,
        moduleName: String = "TestModule"
    ): List<Pair<String, Requirement>> {
        val missing = mutableListOf<Pair<String, Requirement>>()

        for ((defName, consumerScopeFqName, req) in requirements) {
            if (!req.requiresValidation()) continue
            if (req.isProperty) continue

            // Skip @Provided types and framework-provided types (same as real validation path)
            val reqFqName = req.typeKey.fqName?.asString() ?: req.typeKey.classId?.asFqNameString()
            if (reqFqName != null && ProvidedTypeRegistry.isProvided(reqFqName)) continue
            if (reqFqName != null && isWhitelistedType(reqFqName)) continue

            val found = findProviderData(req, provided, consumerScopeFqName)
            if (!found) {
                missing.add(defName to req)
            }
        }

        return missing
    }

    /**
     * Search for a provider matching the requirement using plain data.
     */
    internal fun findProviderData(
        req: Requirement,
        provided: Set<Triple<TypeKey, QualifierValue?, String?>>,
        consumerScopeFqName: String?
    ): Boolean {
        val reqFqName = req.typeKey.fqName
        val reqClassId = req.typeKey.classId

        for ((providerTypeKey, providerQualifier, providerScopeFqName) in provided) {
            val typeMatch = when {
                reqFqName != null && providerTypeKey.fqName != null -> reqFqName == providerTypeKey.fqName
                reqClassId != null && providerTypeKey.classId != null -> reqClassId == providerTypeKey.classId
                else -> false
            }
            if (!typeMatch) continue

            if (!qualifiersMatch(req.qualifier, providerQualifier)) continue

            // Scope visibility
            if (providerScopeFqName == null) return true  // Root scope visible to all
            if (consumerScopeFqName != null && providerScopeFqName == consumerScopeFqName) return true
        }

        return false
    }

    internal fun qualifiersMatchPublic(a: QualifierValue?, b: QualifierValue?): Boolean = qualifiersMatch(a, b)
}
