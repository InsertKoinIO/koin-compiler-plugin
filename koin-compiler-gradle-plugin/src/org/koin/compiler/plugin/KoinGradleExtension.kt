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
     * Enable DSL safety checks (default: true).
     * When enabled, validates that create() calls inside lambdas are the only instruction.
     * Set to false when migrating from legacy DSL code that has other statements in create lambdas.
     */
    val dslSafetyChecks: Property<Boolean> = objectFactory.property(Boolean::class.java).convention(true)
}
