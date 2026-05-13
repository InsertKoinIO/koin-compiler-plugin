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
     * Force the safety pass to always run, bypassing Kotlin's incremental compilation cache
     * (default: false). Set this on the module containing `startKoin { }` or `@KoinApplication`
     * (typically `:app`) when you need compile-time graph validation to survive incremental builds.
     *
     * Background: Kotlin's incremental compilation skips `compileKotlin` when no source file
     * directly references a changed declaration. For the Koin aggregator module — which usually
     * only calls `startKoin { modules(...) }` — this means changes deep in transitive
     * module bodies do not invalidate the aggregator's compile task, so the full-graph
     * safety check never re-runs. The build silently passes; the failure surfaces at runtime.
     *
     * When `strictSafety = true`, the aggregator's compile task always re-runs (cost: ~that
     * task's compile time per build). Other modules stay fully incremental.
     *
     * Has no effect when `compileSafety = false`.
     *
     * See: https://github.com/InsertKoinIO/koin-compiler-plugin/issues/32
     */
    val strictSafety: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(false)

    /**
     * Append a single AI-assist hint at the end of compilation if any Koin diagnostic fires (default: true).
     * Emitted once per build (not per error), pointing developers to the Kotzilla MCP Server,
     * which can classify Koin errors and walk an AI coding assistant through the fix.
     * Set to false to suppress the CTA.
     * See: https://doc.kotzilla.io/docs/fixIssues/koinMcp
     */
    val aiAssist: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(true)
}
