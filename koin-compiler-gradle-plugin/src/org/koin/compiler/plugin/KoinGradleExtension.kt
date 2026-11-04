package org.koin.compiler.plugin

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/**
 * Gradle extension for configuring the Koin compiler plugin.
 *
 * Usage:
 * ```kotlin
 * koinCompiler {
 *     userLogs = true   // Log component detection and DSL interceptions
 *     debugLogs = true  // Log internal plugin processing for debugging
 * }
 * ```
 */
open class KoinGradleExtension(objectFactory: ObjectFactory) {
    /**
     * Enable user-facing logs.
     * Traces what components are detected and intercepted:
     * - DSL interceptions (single<T>(), factory<T>(), etc.)
     * - Processed annotations (@Singleton, @Factory, @Module, etc.)
     */
    val userLogs: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(false)

    /**
     * Enable debug logs.
     * Traces internal plugin processing for debugging:
     * - FIR phase processing
     * - IR transformation details
     * - Module discovery and registration
     */
    val debugLogs: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(false)

    /**
     * Enable unsafe DSL checks (default: true).
     * When enabled, validates that create() calls inside lambdas are the only instruction.
     * Set to false when migrating from legacy DSL code that has other statements in create lambdas.
     */
    val unsafeDslChecks: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(true)

    /**
     * Skip injection for parameters with default values (default: true).
     * When enabled, non-nullable parameters with Kotlin default values will use
     * the default value instead of being resolved from the DI container.
     * Nullable parameters are not affected (they still use getOrNull()).
     * Parameters with explicit annotations (@Named, @Qualifier, @InjectedParam, @Property)
     * are always injected regardless of this setting.
     */
    val skipDefaultValues: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(true)

    /**
     * Enable compile-time dependency safety checks (default: true).
     * When enabled, validates at compile time that all required dependencies are provided
     * within each @Module. Missing non-nullable dependencies without default values
     * will cause a compilation error.
     * Set to false to disable validation (e.g., when dependencies are provided by external modules).
     */
    val compileSafety: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(true)

    /**
     * Force the safety pass to always run, bypassing Kotlin's incremental compilation cache.
     *
     * When left unset (the default), the Gradle plugin scans source files for `startKoin`,
     * `koinApplication`, or `@KoinApplication` and **auto-enables** this on detected
     * aggregator modules. A lifecycle log line announces the decision. Set explicitly to `true`
     * to force it on regardless of detection.
     *
     * Setting this to `false` turns it off, even when an entry point is detected (1.2.2; a warning
     * says so): incremental builds may then miss graph changes, while clean and CI builds still
     * validate. To enforce it on CI only: `strictSafety = providers.environmentVariable("CI").isPresent`.
     *
     * Background: Kotlin's incremental compilation skips `compileKotlin` when no source file
     * directly references a changed declaration. DSL lambda bodies are not part of any
     * declaration's ABI, so changes inside `module { … }` lambdas do not invalidate the
     * aggregator's compile task even when it explicitly references the module class. The
     * full-graph safety check never re-runs, the build passes silently, and the failure
     * surfaces at runtime.
     *
     * Cost when enabled: the aggregator's `compileKotlin` task always re-runs (no cache, no
     * up-to-date skip), which in practice recompiles the whole module every build. Other modules
     * stay fully incremental.
     *
     * Has no effect when `compileSafety = false`.
     *
     * See: https://github.com/InsertKoinIO/koin-compiler-plugin/issues/32
     */
    val strictSafety: Property<Boolean> = objectFactory.property(Boolean::class.java)

    /** No longer needed: `strictSafety = false` is honored on its own since 1.2.2. */
    @Deprecated("strictSafety = false is honored on its own since 1.2.2. Remove this setting.")
    val strictSafetyForceOff: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(false)

    /**
     * Append a single AI-assist hint at the end of compilation if any Koin diagnostic fires (default: true).
     * Emitted once per Kotlin compilation (not per error), pointing developers to the Kotzilla MCP Server,
     * which can classify Koin errors and walk an AI coding assistant through the fix.
     * Set to false to suppress the CTA.
     * See: https://doc.kotzilla.io/docs/fixIssues/koinMcp
     */
    val aiAssist: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(true)

    /**
     * Severity of the plugin's informational output — `userLogs`/`debugLogs` messages and
     * `@Monitor` tracing-enabled summaries (default: `"warning"`, preserving today's behavior).
     *
     * Set to `"info"` if your build uses `allWarningsAsErrors` / `-Werror`: WARNING-severity
     * output fails such a build even though this is purely informational (issue #73). Does NOT
     * affect real diagnostics (KOIN-Dxxx/Wxxx/etc.) — those stay at their own severity regardless
     * of this setting. See [versionCheckSeverity] for the separate Kotlin-version-gate control.
     */
    val logSeverity: Property<String> = objectFactory.property(String::class.java).convention("warning")

    /**
     * Severity of the "you're on an unverified Kotlin version" compatibility warning (default:
     * `"warning"`). Deliberately a separate setting from [logSeverity]: muting informational
     * plugin noise shouldn't also hide compiler-compatibility risk. Set to `"info"` if this is
     * blocking an `allWarningsAsErrors` build and you've already assessed the compatibility risk.
     */
    val versionCheckSeverity: Property<String> = objectFactory.property(String::class.java).convention("warning")

    /**
     * What to do when a module that starts Koin reaches modules contributing Koin definitions only at
     * runtime (an `implementation` dependency of one of its dependencies): compile safety sees them
     * only indirectly and can miss problems. `"none"` (default) leaves the check off, `"warning"` names
     * each one and the dependency to declare, `"error"` fails the build; case is ignored, any other
     * value fails the build. JVM and Android only: Kotlin/Native, JS and Wasm compilations already
     * see transitive dependencies. Opt-in: it resolves runtime classpaths while Gradle plans the build.
     */
    val hiddenDependencyCheck: Property<String> = objectFactory.property(String::class.java).convention("none")
}
