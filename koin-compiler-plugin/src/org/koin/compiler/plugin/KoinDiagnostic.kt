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
            append("\n  No matching definition found in any declared module.")
            append("\n  Check your declaration with Annotation or DSL.")
        },
    )

    /** KOIN-D003 — A cross-module call-site hint cannot be resolved at app assembly. */
    class MissingCallSiteDeferred(
        type: String,
    ) : KoinDiagnostic(
        code = "KOIN-D003",
        severity = Severity.ERROR,
        message = buildString {
            append("Missing definition: ")
            append(type)
            append("\n  Required by a call site in a dependency module (deferred validation).")
            append("\n  No matching definition found in any declared module.")
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
     * KOIN-D008 — A top-level annotated definition exists locally, but no loaded @Module
     * includes it via @ComponentScan, so same-module call sites would fail at runtime.
     */
    class UnclaimedTopLevelDefinition(
        function: String,
        returnType: String,
    ) : KoinDiagnostic(
        code = "KOIN-D008",
        severity = Severity.ERROR,
        message = "Top-level definition function is not covered by any local @ComponentScan: " +
                "$function -> $returnType\n" +
                "  Add @ComponentScan to a @Module that covers this function's package, or move the function inside a @Module class.",
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
