package org.koin.compiler.plugin

/**
 * Canonical catalog of Koin Compiler Plugin diagnostics.
 *
 * Every user-visible error/warning the plugin emits goes through one of these
 * subclasses. The [code] is stable across releases and is the contract the
 * Kotzilla MCP Server classifier matches on. See:
 * https://doc.kotzilla.io/docs/fixIssues/koinMcp
 */
sealed class KoinDiagnostic(
    val code: String,
    val severity: Severity,
    val message: String,
) {
    enum class Severity { ERROR, WARNING }

    /** KOIN-D001 — A definition parameter has no provider in the visible scope. */
    class MissingBinding(
        type: String,
        qualifier: String?,
        def: String,
        param: String,
        module: String,
        hint: String? = null,
    ) : KoinDiagnostic(
        code = "KOIN-D001",
        severity = Severity.ERROR,
        message = buildString {
            append("Missing dependency: ")
            append(type)
            if (qualifier != null) {
                append(" qualified with ")
                append(qualifier)
            }
            append("\n  required by: ")
            append(def)
            append(" (parameter '")
            append(param)
            append("')")
            append("\n  in module: ")
            append(module)
            if (hint != null) {
                append("\n  Hint: ")
                append(hint)
            }
        },
    )

    /** KOIN-D002 — A `get<T>()` / `koinInject<T>()` call has no matching definition (local graph). */
    class MissingCallSite(
        type: String,
        callFn: String,
    ) : KoinDiagnostic(
        code = "KOIN-D002",
        severity = Severity.ERROR,
        message = buildString {
            append("Missing definition: ")
            append(type)
            append("\n  resolved by: ")
            append(callFn)
            append("<")
            append(type.substringAfterLast('.'))
            append(">()")
            append("\n  No matching definition found in any loaded module.")
            append("\n  Check your declaration with Annotation or DSL.")
        },
    )

    /**
     * KOIN-D003 — A cross-module call-site hint cannot be resolved at app assembly.
     *
     * The call site is in a *dependency* module, so — unlike the local [MissingCallSite] (D002) which
     * has the real IR call expression and reports file:line — the app only sees a `callsite` hint. To
     * make it as actionable as D002 the hint now also carries the resolver function and the dependency
     * module's id, so we can name both (falling back to the bare message when either is absent, e.g.
     * an older hint or a build without `koin.moduleId`).
     */
    class MissingCallSiteDeferred(
        type: String,
        module: String? = null,
    ) : KoinDiagnostic(
        code = "KOIN-D003",
        severity = Severity.ERROR,
        message = buildString {
            // The call site's location is carried as the compiler source-location prefix (clickable
            // in a real Gradle build — see CallSiteValidator), so it isn't repeated in the body.
            append("Missing definition: ")
            append(type)
            append("\n  required by a call site in a dependency module")
            if (module != null) {
                append(" (")
                append(module)
                append(")")
            }
            append(" — deferred validation.")
            append("\n  No matching definition found in any loaded module.")
            append("\n  Check your declaration with Annotation or DSL.")
        },
    )

    /**
     * KOIN-D004 — A constructor-injection cycle exists in the assembled graph.
     *
     * Reported once per unique cycle (canonicalized by rotating to start at the lexicographically
     * smallest node). Edges that pass through `Lazy<T>`, nullable, `@InjectedParam`, `@Provided`,
     * `@ScopeId`, `List<T>`, `@Property`, or default-valued parameters are not edges in the cycle
     * graph — i.e., `Lazy<T>` is the canonical way to break a constructor cycle at runtime.
     */
    class CircularDependency(
        cycle: List<String>,
    ) : KoinDiagnostic(
        code = "KOIN-D004",
        severity = Severity.ERROR,
        message = buildString {
            append("Circular dependency detected:\n  ")
            // cycle is path ending back at the start, e.g. [A, B, C, A]
            append(cycle.joinToString(" → "))
            append("\n  Break with Lazy<T> injection or refactor to remove the cycle.")
        },
    )

    /**
     * KOIN-D005 — A `parametersOf(...)` call at a `get<T>()` / `inject<T>()` / `koinInject<T>()`
     * site doesn't match the target definition's `@InjectedParam` slots (count or type).
     *
     * Fires only when the call site has a statically detectable `parametersOf(...)` call (single
     * call at the top of the trailing lambda). Hand-written lambdas that resolve params by type
     * (`{ params -> Foo(params.get<X>()) }`) are intentionally skipped to avoid false positives.
     *
     * Reason discriminates the two failure modes:
     *  - [Reason.ARITY] — number of arguments differs from `@InjectedParam` slot count.
     *  - [Reason.TYPE]  — positional type at index `i` doesn't match slot `i` (raw FqName
     *    equality + nullability rule; subtype matching is a planned follow-up).
     */
    class MismatchedInjectedParams(
        target: String,
        expected: List<String>,
        actual: List<String>,
        reason: Reason,
    ) : KoinDiagnostic(
        code = "KOIN-D005",
        severity = Severity.ERROR,
        message = buildString {
            append("Mismatched parametersOf(...) for ")
            append(target)
            append(": ")
            append(
                when (reason) {
                    Reason.ARITY -> "expected ${expected.size} argument(s), got ${actual.size}"
                    Reason.TYPE -> "type mismatch"
                }
            )
            append("\n  Expected @InjectedParam slots: ")
            append(if (expected.isEmpty()) "(none)" else expected.joinToString(", "))
            append("\n  Got parametersOf arguments: ")
            append(if (actual.isEmpty()) "(none)" else actual.joinToString(", "))
        },
    ) {
        enum class Reason { ARITY, TYPE }
    }

    /**
     * KOIN-D006 — A `get<T>()` / `inject<T>()` / `koinInject<T>()` call site is missing
     * `parametersOf(...)` although the target definition requires `@InjectedParam` arguments.
     *
     * Fires only when the call site's trailing lambda doesn't statically contain `parametersOf`
     * AND the target's def is known (locally or via the `injectedparams_*` cross-module hint).
     */
    class MissingInjectedParams(
        target: String,
        expected: List<String>,
        callFn: String,
    ) : KoinDiagnostic(
        code = "KOIN-D006",
        severity = Severity.ERROR,
        message = buildString {
            append(target)
            append(" requires ")
            append(expected.size)
            append(" injected param(s) but ")
            append(callFn)
            append("<")
            append(target.substringAfterLast('.'))
            append(">() has no parametersOf(...) at the call site.")
            append("\n  Expected @InjectedParam slots: ")
            append(if (expected.isEmpty()) "(none)" else expected.joinToString(", "))
            append("\n  Pass them via parametersOf(...) inside the trailing lambda.")
        },
    )

    /**
     * KOIN-D007 — A definition's binding type is (or extends) a `suspend` function type.
     *
     * Koin runtime does not currently support suspend function injection — the wiring would
     * compile (the type-args defaulting in `KoinModuleFirGenerator.classLikeTypeWithDefaultArgs`
     * keeps the IR valid for the hint) but the runtime semantics aren't in place: any attempt
     * to resolve through the suspend supertype will misbehave, and the user only finds out at
     * runtime. Blocking the compile is safer than letting silently-broken code ship.
     *
     * Fires for:
     *  - `@Factory fun foo(...): MyUseCase` where `fun interface MyUseCase : suspend (P) -> R`
     *  - any other `@Single` / `@Factory` / `@Scoped` whose return type or explicit binds
     *    transitively references `kotlin.coroutines.SuspendFunctionN`
     *
     * Lifts once Koin core ships suspend DSL wiring (tracked: InsertKoinIO/koin-compiler-plugin#16).
     */
    class UnsupportedSuspendBinding(
        target: String,
        suspendType: String,
    ) : KoinDiagnostic(
        code = "KOIN-D007",
        severity = Severity.ERROR,
        message = buildString {
            append("Unsupported binding: ")
            append(target)
            append(" extends ")
            append(suspendType)
            append("\n  Suspend function injection is not yet supported by Koin runtime.")
            append("\n  Remove the @Single / @Factory / @Scoped registration, or refactor the binding type to not extend a suspend function.")
            append("\n  Tracked at https://github.com/InsertKoinIO/koin-compiler-plugin/issues/16")
        },
    )

    /**
     * KOIN-D008 — Two distinct DSL module ids encode to the same identifier when generating
     * cross-module hint function names (see [KoinPluginConstants.flattenFqNameForHint]).
     *
     * That encoder collapses `.` and `$` to `_` so a cross-module consumer can independently
     * reconstruct a producer's hint name without any lookup table — but two distinct ids CAN
     * encode identically (e.g. `p.q_r.mod` and `p.q.r_mod` both flatten to `p_q_r_mod`). Left
     * undetected this produces either a silently-wrong hint (JVM overwrites one facade class
     * with the other) or a KLIB `SignatureClashDetector` `AssertionError` with no source
     * location (native/wasm targets) — a hard error naming both raw ids is far more actionable
     * than either failure mode.
     *
     * Same-compilation-unit detection only (Tier 1): this catches two colliding module ids
     * declared in one Gradle module's own compile. A true cross-Gradle-module collision is a
     * separate, deferred problem (see release notes for 1.1.0).
     *
     * No opt-out: there is no legitimate scenario where two distinct module ids should collide
     * here — this always means two modules will be silently confused with each other.
     */
    class DuplicateModuleHintIdentity(
        moduleIdA: String,
        moduleIdB: String,
        encoded: String,
    ) : KoinDiagnostic(
        code = "KOIN-D008",
        severity = Severity.ERROR,
        message = buildString {
            append("Module id collision: '")
            append(moduleIdA)
            append("' and '")
            append(moduleIdB)
            append("' both encode to '")
            append(encoded)
            append("' when generating cross-module hint names.")
            append("\n  Rename one of these `module { }` declarations so they no longer collide")
            append(" once non-alphanumeric characters are replaced with '_'.")
        },
    )

    /**
     * KOIN-W001 — A DSL module is not loaded at `startKoin`, so its definitions are unreachable.
     *
     * Warning, not error: a user mid-refactor commonly has a module defined but not yet wired
     * into `modules(...)` / `includes(...)`. Failing the build would force them to comment the
     * module out to keep working. The W prefix matches the catalog's warning convention
     * (KOIN-W*** / KOIN-M*** are warnings; KOIN-D*** / KOIN-E*** / KOIN-A*** are errors).
     */
    class UnreachableModule(
        module: String,
        types: List<String>,
    ) : KoinDiagnostic(
        code = "KOIN-W001",
        severity = Severity.WARNING,
        message = "Module '$module' is not loaded at startKoin — ${types.size} definitions unreachable: " +
            types.joinToString(", ") +
            "\n  Add it to modules() or includes() to make these definitions available",
    )

    /**
     * KOIN-W003 — An entry point (`startKoin` / `koinApplication` / `koinConfiguration`) was given a
     * module set that is NOT statically resolvable — a conditional (`modules(if (x) A else B)`), a
     * spread of a runtime list (`modules(*list)`), or a variable. The set of modules actually loaded
     * depends on runtime values, so the plugin cannot assemble and verify the full graph at compile
     * time for this root.
     *
     * Warning, not error, and never silent: compile-time safety fundamentally cannot verify a
     * runtime-decided graph, but hiding that would let a green build imply a guarantee it never made
     * (the doctrine's worst failure class). We disclose it here; the graph is validated at runtime by
     * Koin's `checkModules()`. Any statically-visible modules in the call are still verified normally
     * (this only flags that verification is partial/unverifiable, not that anything is wrong).
     */
    class UnverifiableDynamicGraph(
        entry: String,
        origin: String?,
    ) : KoinDiagnostic(
        code = "KOIN-W003",
        severity = Severity.WARNING,
        message = buildString {
            append("Graph not verifiable at compile time: ")
            append(entry)
            append(" is loaded with a dynamically-computed module set (a conditional, spread, or ")
            append("variable), so the assembled graph is unknowable here.")
            if (origin != null) {
                append("\n  at: ")
                append(origin)
            }
            append("\n  Compile-time dependency checks are skipped for this entry point; it is ")
            append("validated at runtime via checkModules(). Pass module classes directly — e.g. ")
            append("modules(MyModule::class) — to enable full compile-time verification.")
        },
    )

    /**
     * KOIN-W004 — An entry point requests `@Configuration(...)` modules under a NON-DEFAULT label
     * (`@KoinApplication(configurations = ["..."])`). Cross-module auto-discovery for a custom label
     * is relayed only one `implementation` hop past a module that itself sits on this compilation's
     * classpath — see `KoinAnnotationProcessor.relayConfigurationHints`'s kdoc for why: unlike the
     * "default" label (always queried), an arbitrary label can't be looked up across the classpath
     * without knowing its name in advance, so there's no way to relay a label this compilation never
     * locally saw a reason to look for.
     *
     * Warning, not error: most custom-labeled setups work fine (the module IS within one hop, or is a
     * direct/api dependency). This discloses a real coverage gap rather than claiming a guarantee the
     * plugin cannot make — the doctrine's worst failure class is a green build implying more than it
     * verified. Fires once per (entry point, label) per compilation.
     */
    class CustomConfigurationLabelRelayLimited(
        label: String,
        entry: String,
    ) : KoinDiagnostic(
        code = "KOIN-W004",
        severity = Severity.WARNING,
        message = "Custom @Configuration label '$label' requested by $entry: cross-module discovery " +
            "for non-default labels is only relayed one 'implementation' dependency hop past a " +
            "module already on this compilation's classpath.\n" +
            "  A @Configuration(\"$label\") module 2+ hops away may not be auto-discovered here. " +
            "If a module you expect to be picked up under this label is missing, add it as a direct " +
            "(or api) dependency, or list it explicitly via @KoinApplication(modules = [...]).",
    )

    /**
     * KOIN-W005 — A dependency module reachable only via a classpath-invisible `includes()`/
     * `@Configuration` edge (2+ `implementation` hops away) contributes a definition kind that
     * [org.koin.compiler.plugin.ir.KoinAnnotationProcessor.relayIncludedModuleHints] cannot relay
     * yet: a `@Module`-function definition (`Definition.FunctionDef`), or a qualified/`funcreqs`-
     * carrying top-level provider function (`Definition.TopLevelFunctionDef`). Only class-based
     * definitions and unqualified top-level functions are relayed today.
     *
     * Warning, not error: the definition still exists and works at runtime (Koin resolves it via the
     * real dependency graph); only THIS compilation's compile-time visibility of it is limited. Per
     * doctrine, a silently incomplete relay is worse than disclosing the gap. Fires once per
     * (owning module, definition) per compilation.
     */
    class UnrelayedFunctionDefinition(
        owningModule: String,
        definitionName: String,
    ) : KoinDiagnostic(
        code = "KOIN-W005",
        severity = Severity.WARNING,
        message = "Definition '$definitionName' in dependency module '$owningModule' (reachable only " +
            "2+ 'implementation' hops away) is a function-based provider that cross-module relay " +
            "cannot re-publish yet — it stays invisible to this compilation's compile-time checks, " +
            "though it still resolves correctly at runtime.\n" +
            "  To restore compile-time visibility here, add a direct (or api) dependency on the " +
            "module that owns it.",
    )

    /**
     * KOIN-W006 — a `bind`/`binds` call's argument could not be resolved to a compile-time-known
     * type (or list of them) — anything other than a literal `X::class` / `arrayOf(X::class, ...)` /
     * `listOf(X::class, ...)`, e.g. a variable holding a precomputed array or a function call that
     * builds one.
     *
     * Warning, not silent drop: the binding still works fine at runtime (Koin resolves it via its
     * own `secondaryTypes` mechanism, independent of anything this plugin does), but THIS
     * COMPILATION's compile-time view of the definition is missing that bound type — every consumer
     * of it may see a false KOIN-D001 that nothing in the user's code caused. Per doctrine, silently
     * capturing an incomplete binding list is worse than disclosing the gap (same shape as W005).
     */
    class UnresolvedBindArgument(
        defName: String,
        functionName: String,
    ) : KoinDiagnostic(
        code = "KOIN-W006",
        severity = Severity.WARNING,
        message = "$defName's $functionName(...) argument could not be resolved to a compile-time-known " +
            "type — this bound interface is invisible to compile-time dependency checks here, though " +
            "it still resolves correctly at runtime.\n" +
            "  Use a literal class reference (X::class) or a literal array/list of them for full " +
            "compile-time visibility.",
    )

    /**
     * KOIN-W007 — A DSL definition's own dependencies could not be statically derived, so
     * compile-time validation is skipped for THIS definition (it still registers and works fine
     * at runtime; only its own requirements go unchecked).
     *
     * Fires for a hand-written DSL lambda body (`single<T> { someExpression }`,
     * `factory<T> { ... }`, etc.) that isn't `create(::T)` — opaque by construction; the plugin
     * never introspects or regenerates this code, so it cannot know what a `get<X>()` call inside
     * it requires (those individual `get()` calls are still separately validated as call sites —
     * this warning is specifically about the definition's OWN requirement list, used for e.g.
     * cycle detection, not a total blind spot).
     *
     * Does NOT fire for Koin's own constructor-shorthand DSL (`singleOf`/`factoryOf`/`scopedOf`/
     * `viewModelOf`) — those resolve to one `IrFunctionReference` regardless of arity, the same
     * shape `create(::T)` already resolves, so their requirements ARE derived (see
     * `KoinDSLTransformer.collectConstructorShorthandDef`); no maintenance cost was actually
     * avoided by treating them as opaque.
     *
     * Warning, not silent drop: per doctrine, a silently incomplete validation is worse than
     * disclosing the gap (same shape as W005/W006). Local-only — fires only for a definition
     * DECLARED in this compilation, not for one merely consumed via a dependency's cross-module
     * hint (same locality rule as W001: the DSL-shape choice is that module's own concern).
     */
    class UnvalidatedDslExpression(
        target: String,
    ) : KoinDiagnostic(
        code = "KOIN-W007",
        severity = Severity.WARNING,
        message = "Compile-time validation skipped for '$target': registered via a hand-written " +
            "DSL lambda body the plugin cannot statically analyze.\n" +
            "  The definition is still visible to consumers and works correctly at runtime — only " +
            "its own dependencies go unchecked by this compilation's compile-time checks.\n" +
            "  Use single<T>()/factory<T>()/create(::T)/singleOf(::T) (optionally chained with " +
            ".bind<Interface>()) for full compile-time validation.",
    )

    /** KOIN-A001 — `@KoinViewModel` used without `io.insert-koin:koin-core-viewmodel`. */
    class MissingViewModelArtifact(
        def: String,
    ) : KoinDiagnostic(
        code = "KOIN-A001",
        severity = Severity.ERROR,
        message = "@KoinViewModel definition '$def' cannot be generated: 'buildViewModel' is not on classpath. " +
            "Add dependency: io.insert-koin:koin-core-viewmodel",
    )

    /** KOIN-A002 — `@KoinWorker` used without `io.insert-koin:koin-android-workmanager`. */
    class MissingWorkerArtifact(
        def: String,
    ) : KoinDiagnostic(
        code = "KOIN-A002",
        severity = Severity.ERROR,
        message = "@KoinWorker definition '$def' cannot be generated: 'buildWorker' is not on classpath. " +
            "Add dependency: io.insert-koin:koin-android-workmanager",
    )

    /** KOIN-A003 — `@Module` used without `io.insert-koin:koin-core` (no `org.koin.dsl.module`). */
    class MissingCoreArtifact(
        moduleClassName: String,
    ) : KoinDiagnostic(
        code = "KOIN-A003",
        severity = Severity.ERROR,
        message = "Cannot generate $moduleClassName.module(): org.koin.dsl.module() not found on classpath. " +
            "Please add io.insert-koin:koin-core to your dependencies.",
    )

    /** KOIN-S001 — `create(::T)` is not the only instruction in its lambda. */
    class UnsafeDsl(
        target: String,
    ) : KoinDiagnostic(
        code = "KOIN-S001",
        severity = Severity.ERROR,
        message = "create(::$target) must be the only instruction in the lambda. " +
            "Other statements are not allowed when using create(). " +
            "To disable this check, set koinCompiler { unsafeDslChecks = false } in your build.gradle.kts",
    )

    /** KOIN-P001 — `@Property` has no matching `@PropertyValue` default in the same module. */
    class MissingPropertyValue(
        key: String,
        def: String,
        module: String,
    ) : KoinDiagnostic(
        code = "KOIN-P001",
        severity = Severity.WARNING,
        message = "Missing @PropertyValue default: \"$key\" — no @PropertyValue(\"$key\") found for " +
            "$def in module $module. Property must be provided at runtime via properties().",
    )

    /** KOIN-M001 — `@Monitor` used without the Kotzilla SDK on classpath. */
    class MonitorNoSdk : KoinDiagnostic(
        code = "KOIN-M001",
        severity = Severity.WARNING,
        message = "@Monitor: Kotzilla SDK not found on classpath - monitoring disabled",
    )
}
