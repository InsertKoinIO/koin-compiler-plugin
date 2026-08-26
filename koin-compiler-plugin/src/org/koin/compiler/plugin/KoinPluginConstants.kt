package org.koin.compiler.plugin

/**
 * Shared constants for the Koin compiler plugin.
 *
 * Centralizes option keys, definition type names, and other constants
 * used across both the compiler plugin and Gradle plugin.
 */
object KoinPluginConstants {

    // ================================================================================
    // Auto-binding exclusion policy (issues #43, #64)
    // ================================================================================

    /**
     * Supertypes/markers that are NEVER auto-bound as a definition's exposed type.
     *
     * Binding these would let `get<KoinComponent>()` / `get<ViewModel>()` resolve to an
     * arbitrary annotated component — silent wrong-instance resolution. They are framework
     * plumbing or marker contracts, not DI-resolvable types: a definition is always registered
     * under its own type and its genuine domain interfaces, never these.
     *
     *  - `kotlin.Any` — the root type; binding it is meaningless.
     *  - `org.koin.core.component.KoinComponent` / `KoinScopeComponent` — Koin marker interfaces (#43).
     *  - `androidx.lifecycle.ViewModel` / `AndroidViewModel` — framework base classes; a
     *    `@KoinViewModel` registers under its own type, not the ViewModel supertype (#64).
     *
     * Applied by BOTH auto-binding detectors — the IR path (`detectAutoBindings`) and the FIR
     * cross-module hint path (`KoinModuleFirGenerator.detectBindingClassIds`) — so the exclusion
     * holds in the same module and across `@ComponentScan` module boundaries. An explicit
     * `@Single(binds = [...])` still binds whatever the user lists; this only governs
     * AUTO-detected bindings.
     */
    val AUTO_BIND_EXCLUDED_SUPERTYPES: Set<String> = setOf(
        "kotlin.Any",
        "org.koin.core.component.KoinComponent",
        "org.koin.core.component.KoinScopeComponent",
        "androidx.lifecycle.ViewModel",
        "androidx.lifecycle.AndroidViewModel",
    )

    // ================================================================================
    // Plugin Options - These names must match between compiler and Gradle plugins
    // ================================================================================

    /** Option to enable user-facing logs (component detection, DSL interceptions). */
    const val OPTION_USER_LOGS = "userLogs"

    /** Option to enable debug logs (internal plugin processing). */
    const val OPTION_DEBUG_LOGS = "debugLogs"

    /** Option to enable unsafe DSL checks (validates create() is the only instruction in lambda). */
    const val OPTION_UNSAFE_DSL_CHECKS = "unsafeDslChecks"

    /** Option to skip injection for parameters with default values. */
    const val OPTION_SKIP_DEFAULT_VALUES = "skipDefaultValues"

    /** Option to enable compile-time dependency safety checks. */
    const val OPTION_COMPILE_SAFETY = "compileSafety"

    /** Option to append a single AI-assist CTA at the end of compilation if any Koin diagnostic fires. */
    const val OPTION_AI_ASSIST = "aiAssist"

    /**
     * Option carrying a stable, Gradle-module-unique identifier (typically `project.path`).
     * Used as the leading segment of synthetic hint file names so that two Gradle modules
     * producing hints for the same target type don't collide at dex merge time.
     * Falls back to the FIR module-data name when absent.
     */
    const val OPTION_MODULE_ID = "moduleId"

    /**
     * Option controlling the severity of the plugin's informational output — [user]/[debug]/
     * [userFir]/[debugFir]/[warn] in [KoinPluginLogger] (issue #73: under Gradle's
     * `allWarningsAsErrors`, WARNING-severity output fails the build even though it's purely
     * informational). Values: `"warning"` (default, preserves prior behavior) | `"info"` (safe
     * under `allWarningsAsErrors`). Does NOT affect real diagnostics (KOIN-Dxxx/Wxxx/etc, see
     * [OPTION_VERSION_CHECK_SEVERITY] for the separate Kotlin-version-gate setting).
     */
    const val OPTION_LOG_SEVERITY = "logSeverity"

    /**
     * Option controlling the severity of the Kotlin-version-compatibility warning, independent
     * of [OPTION_LOG_SEVERITY] — a user muting informational plugin noise should not also lose
     * visibility into "you're on an unverified Kotlin version" by the same toggle. Values:
     * `"warning"` (default) | `"info"`.
     */
    const val OPTION_VERSION_CHECK_SEVERITY = "versionCheckSeverity"

    /**
     * URL printed in the AI-assist CTA.
     *
     * Short redirect to the canonical doc page at https://doc.kotzilla.io/docs/fixIssues/koinMcp.
     * Pinned by [org.koin.compiler.plugin.KoinDiagnosticTest] — changing this string is a public
     * contract change and must be coordinated with the redirect on kotzilla.io.
     */
    const val AI_ASSIST_CTA_URL = "https://kotzilla.io/koin-mcp"

    // ================================================================================
    // Definition Types - Used for hint functions and logging
    // ================================================================================

    /** Definition type for single/singleton definitions. */
    const val DEF_TYPE_SINGLE = "single"

    /** Definition type for factory definitions. */
    const val DEF_TYPE_FACTORY = "factory"

    /** Definition type for scoped definitions. */
    const val DEF_TYPE_SCOPED = "scoped"

    /** Definition type for viewModel definitions. */
    const val DEF_TYPE_VIEWMODEL = "viewmodel"

    /** Definition type for worker definitions. */
    const val DEF_TYPE_WORKER = "worker"

    /** All supported definition types. */
    val ALL_DEFINITION_TYPES = listOf(
        DEF_TYPE_SINGLE,
        DEF_TYPE_FACTORY,
        DEF_TYPE_SCOPED,
        DEF_TYPE_VIEWMODEL,
        DEF_TYPE_WORKER
    )

    // ================================================================================
    // Hint Functions - For cross-module discovery
    // ================================================================================

    /** Package where hint functions are generated for cross-module discovery. */
    const val HINTS_PACKAGE = "org.koin.plugin.hints"

    /** Prefix for configuration hint functions (e.g., configuration_default). */
    const val HINT_FUNCTION_PREFIX = "configuration_"

    /** Prefix for definition hint functions (e.g., definition_single). */
    const val DEFINITION_HINT_PREFIX = "definition_"

    /** Prefix for function definition hint functions (e.g., definition_function_single). */
    const val DEFINITION_FUNCTION_HINT_PREFIX = "definition_function_"

    /** Prefix for module-scoped component scan hint functions (e.g., componentscan_comExampleCoreModule_single). */
    const val COMPONENT_SCAN_HINT_PREFIX = "componentscan_"

    /** Prefix for module-scoped component scan function hint functions (e.g., componentscanfunc_comExampleCoreModule_single). */
    const val COMPONENT_SCAN_FUNCTION_HINT_PREFIX = "componentscanfunc_"

    /** Prefix for roster-hint parameter names that enumerate per-qualifier entries (e.g., q_initFlagsAndLogging). */
    const val COMPONENT_SCAN_FUNCTION_ROSTER_PARAM_PREFIX = "q_"

    /** Prefix for per-function definition hints inside @Module classes (e.g., moduledef_comExampleDaosModule_providesTopicDao). */
    const val MODULE_DEFINITION_HINT_PREFIX = "moduledef_"

    /** Prefix for DSL definition hints (e.g., dsl_single, dsl_factory). */
    const val DSL_DEFINITION_HINT_PREFIX = "dsl_"

    /**
     * Prefix for `@InjectedParam` shape hints (e.g., `injectedparams_com_example_A`).
     * The hint function's signature carries the shape: each `@InjectedParam` slot becomes a
     * value parameter with the slot's type and nullability. Consumers read arity/types/nullability
     * directly from `IrFunction.valueParameters`. Used by KOIN-D005/D006 to validate
     * `parametersOf(...)` at `get<T>()` / `inject<T>()` / `koinInject<T>()` call sites
     * across module boundaries.
     */
    const val INJECTED_PARAMS_HINT_PREFIX = "injectedparams_"

    /**
     * Prefix for the A3 function-requirements carrier hint (Gate-3). For a function-based provider
     * discovered cross-module as [Definition.ExternalFunctionDef] — a top-level `@Single fun`
     * reached via a dependency's @ComponentScan roster — the hint carries what the function NEEDS
     * (its must-validate constructor/parameter requirements) so the A3 verifier at the consumer's
     * entry point can check them. Without it, ExternalFunctionDef.requirements is empty and the
     * verifier is blind to the provider's transitive deps (a silent false negative).
     *
     * The hint IS the shape: `funcreqs_<flat-return-fqn>(param0: T0, param1: T1, …)`, one value
     * parameter per must-validate requirement, mirroring [INJECTED_PARAMS_HINT_PREFIX]. Consumers
     * rebuild the requirement list by walking `IrFunction.valueParameters` — no string parsing.
     */
    const val FUNCTION_REQS_HINT_PREFIX = "funcreqs_"

    /**
     * Name of the requirements carrier for one function provider, keyed by return type AND qualifier.
     *
     * The qualifier belongs in the key because two qualified providers of the same type is ordinary
     * Koin (`@Named("auth")` / `@Named("plain")` returning `HttpClient`). Keyed on the return type
     * alone, the second provider's carrier was never emitted and BOTH consumers decoded the first
     * one's requirements: one provider's dependencies went unvalidated (a silent false negative) and
     * the other's were falsely attributed to it. This is also the key the consumer already dedupes
     * ExternalFunctionDefs by, so the two now agree.
     *
     * Unqualified providers keep the bare `funcreqs_<flat-return-fqn>` name, so nothing that existed
     * before this change moves. Qualified ones get a `__q_<sanitized>` suffix, matching the
     * convention `componentscanfunc_…__q_…` already uses — which also keeps signatures distinct on
     * KLIB, where duplicates are a hard error rather than a silent overwrite.
     */
    fun funcReqsHintFunctionName(returnFqn: String, qualifierDiscriminator: String?): String {
        val flat = flattenFqNameForHint(returnFqn)
        return if (qualifierDiscriminator == null) "$FUNCTION_REQS_HINT_PREFIX$flat"
        else "$FUNCTION_REQS_HINT_PREFIX${flat}__q_$qualifierDiscriminator"
    }

    /**
     * Flatten an FqName (dots → underscores) into a Kotlin-identifier-safe segment usable as
     * the suffix of an [INJECTED_PARAMS_HINT_PREFIX] hint function name. `$` (nested-class
     * separator in some FqName renderings) also collapses to `_`.
     */
    fun flattenFqNameForHint(fqName: String): String =
        fqName.replace('.', '_').replace('$', '_')

    /** Function name for qualifier annotation hint functions (e.g., qualifier). */
    const val QUALIFIER_HINT_NAME = "qualifier"

    /** Function name for call-site hints (deferred validation across modules). */
    const val CALLSITE_HINT_NAME = "callsite"

    /** Prefix for module property ID parameter in DSL hint functions (cross-module reachability). */
    const val DSL_MODULE_PARAM_PREFIX = "module_"

    /**
     * Prefix for the DSL includes-edge hint — the topology carrier for `module { includes(…) }`.
     *
     * A DSL module's membership lives in its lambda BODY, which is not part of any declaration's
     * ABI, so an `includes()` edge declared in a dependency module does not survive compilation.
     * Without this hint a consumer only knows the edges it can walk locally, so any module reached
     * ONLY through a dependency's `includes()` looks unreachable — its definitions get dropped from
     * the provider set and every consumer of them hard-errors (a false KOIN-D001 on a graph that
     * resolves fine at runtime, plus a false KOIN-W001). This is the DSL analog of what
     * `@Module(includes = […])` gives the annotation side for free, since that IS ABI.
     *
     * Shape: `dslincludes_<flattened-owner-module-id>(module_<included$module$id>: Unit, …)`.
     * The owner's id is in the NAME (not a parameter) so every module val gets a unique signature —
     * all parameters are `Unit`-typed, so two modules with the same include count would otherwise
     * collide on JVM/KLIB. Consumers rebuild the name from a module id they already know and read
     * the edges off the parameter names, walking them breadth-first so relay chains resolve.
     */
    const val DSL_INCLUDES_HINT_PREFIX = "dslincludes_"

    /**
     * Marker parameter on a [DSL_INCLUDES_HINT_PREFIX] hint: this module's `includes(...)` had an
     * argument the producer could not resolve, so the edges it carries are PARTIAL.
     *
     * Incompleteness must travel with the edges. Without it a consumer reads a partial list as the
     * whole truth and reports everything beyond it unreachable — a false KOIN-D001/D002/W001 on a
     * graph that resolves fine at runtime, one module away from where the ambiguity actually is.
     */
    const val DSL_INCLUDES_INCOMPLETE_MARKER = "incomplete_topology"

    /** Hint function name carrying the `includes()` edges declared by [ownerModuleId]'s `module { }`. */
    fun dslIncludesHintFunctionName(ownerModuleId: String): String =
        "$DSL_INCLUDES_HINT_PREFIX${flattenFqNameForHint(ownerModuleId)}"

    /**
     * Prefix for the annotation includes-edge hint — the topology carrier for
     * `@Module(includes = [...])` when the included class is not on the READER's classpath.
     *
     * `@Module(includes=[X::class])` IS ABI, so a direct reader can normally resolve `X::class` by
     * walking the annotation off the classpath — unlike DSL's `includes()`, which lives in a lambda
     * body. That resolvability breaks down one hop further: reading X's OWN `includes=[...]`
     * requires X's included classes to *also* be on the reader's classpath, and Gradle
     * `implementation` (non-transitive) scoping deliberately hides anything beyond a direct
     * dependency. X can always resolve its OWN `includes` list in its own compilation — it's a
     * `KClass` literal array, which wouldn't compile otherwise — so X re-publishes that list as a
     * hint, the same way [DSL_INCLUDES_HINT_PREFIX] does for DSL `module { includes(...) }`.
     *
     * Shape: `annotationincludes_<flattened-owner-fqname>(module_<included-fqname>: Unit, …)` —
     * reuses [DSL_MODULE_PARAM_PREFIX] for the parameter encoding, so decoding is symmetric with the
     * DSL carrier. Kept in a separate namespace from `dslincludes_*` since the two graphs are
     * semantically distinct even where an owner id happens to coincide.
     */
    const val ANNOTATION_INCLUDES_HINT_PREFIX = "annotationincludes_"

    /** Hint function name carrying the `includes=[...]` edges declared by [ownerModuleId]'s `@Module`. */
    fun annotationIncludesHintFunctionName(ownerModuleId: String): String =
        "$ANNOTATION_INCLUDES_HINT_PREFIX${flattenFqNameForHint(ownerModuleId)}"

    /** Default label for @Configuration modules. */
    const val DEFAULT_LABEL = "default"

    // ================================================================================
    // Generated Function Names
    // ================================================================================

    /** Name of the generated module extension function. */
    const val MODULE_FUNCTION_NAME = "module"

    // ================================================================================
    // Qualifier Name Encoding — for embedding qualifier strings in Kotlin identifiers
    // ================================================================================

    /**
     * Sanitize a qualifier name for use in a Kotlin identifier (hint parameter name).
     *
     * Characters not valid in Kotlin identifiers are escaped as `$XX` where XX is
     * the lowercase 2-digit hex code of the character. Literal `$` is escaped as `$$`.
     *
     * Example: `"my.service-1"` → `"my$2eservice$2d1"`
     */
    fun sanitizeQualifierName(name: String): String = buildString(name.length) {
        for (ch in name) {
            when {
                ch == '$' -> append("$$")
                ch.isLetterOrDigit() || ch == '_' -> append(ch)
                else -> {
                    append('$')
                    append(ch.code.toString(16).padStart(2, '0'))
                }
            }
        }
    }

    /**
     * Reverse [sanitizeQualifierName]: decode a sanitized identifier back to the original
     * qualifier name.
     *
     * Example: `"my$2eservice$2d1"` → `"my.service-1"`
     */
    fun unsanitizeQualifierName(encoded: String): String = buildString(encoded.length) {
        var i = 0
        while (i < encoded.length) {
            val ch = encoded[i]
            if (ch == '$' && i + 1 < encoded.length) {
                if (encoded[i + 1] == '$') {
                    append('$')
                    i += 2
                } else if (i + 2 < encoded.length) {
                    val hex = encoded.substring(i + 1, i + 3)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        append(code.toChar())
                        i += 3
                    } else {
                        append(ch)
                        i++
                    }
                } else {
                    append(ch)
                    i++
                }
            } else {
                append(ch)
                i++
            }
        }
    }
}
