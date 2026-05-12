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

    /** KOIN-W001 — A DSL module is not loaded at `startKoin`, so its definitions are unreachable. */
    class UnreachableModule(
        module: String,
        types: List<String>,
    ) : KoinDiagnostic(
        code = "KOIN-W001",
        severity = Severity.ERROR,
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
