package org.koin.compiler.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.koin.compiler.plugin.ir.KoinIrExtension

/**
 * Centralized logger for the Koin compiler plugin.
 * Provides conditional logging based on user configuration.
 *
 * Two log levels:
 * - User logs: Component detection, DSL interceptions, annotations processed (enabled by userLogs=true)
 * - Debug logs: Internal plugin processing details (enabled by debugLogs=true)
 *
 * Performance: All logging functions are inline with lambda parameters.
 * When logging is disabled, the message lambda is never invoked, avoiding
 * string concatenation overhead at call sites.
 *
 * FIR extensions don't receive configuration directly, so we store it globally.
 */
object KoinPluginLogger {
    @Volatile
    @PublishedApi
    internal var messageCollector: MessageCollector = MessageCollector.NONE

    @Volatile
    var userLogsEnabled: Boolean = false
        private set

    @Volatile
    var debugLogsEnabled: Boolean = false
        private set

    @Volatile
    var unsafeDslChecksEnabled: Boolean = true
        private set

    @Volatile
    var skipDefaultValuesEnabled: Boolean = true
        private set

    @Volatile
    var compileSafetyEnabled: Boolean = true
        private set

    /** LookupTracker from compiler configuration, for direct IC lookup recording. */
    @Volatile
    var lookupTracker: LookupTracker? = null
        private set

    /**
     * Initialize the logger with configuration from the compiler.
     */
    fun init(collector: MessageCollector, userLogs: Boolean, debugLogs: Boolean, unsafeDslChecks: Boolean = true, skipDefaultValues: Boolean = true, compileSafety: Boolean = true, lookupTracker: LookupTracker? = null) {
        messageCollector = collector
        userLogsEnabled = userLogs
        debugLogsEnabled = debugLogs
        unsafeDslChecksEnabled = unsafeDslChecks
        skipDefaultValuesEnabled = skipDefaultValues
        compileSafetyEnabled = compileSafety
        this.lookupTracker = lookupTracker
    }

    /**
     * Log a user-facing message.
     * These are high-level messages about what the plugin is doing:
     * - DSL interceptions (single<T>(), factory<T>(), etc.)
     * - Annotations detected (@Singleton, @Factory, @Module)
     * - Generated modules and their contents
     *
     * The message lambda is only invoked if logging is enabled.
     */
    inline fun user(message: () -> String) {
        if (userLogsEnabled) {
            messageCollector.report(CompilerMessageSeverity.WARNING, "[Koin] ${message()}")
        }
    }

    /**
     * Log a debug message.
     * These are detailed internal processing messages for debugging:
     * - FIR phase details
     * - IR transformation internals
     * - Discovery and registration steps
     *
     * The message lambda is only invoked if logging is enabled.
     */
    inline fun debug(message: () -> String) {
        if (debugLogsEnabled) {
            messageCollector.report(CompilerMessageSeverity.WARNING, "[Koin-Debug] ${message()}")
        }
    }

    /**
     * Log a warning that is always emitted regardless of userLogs/debugLogs settings.
     * Use for critical messages that should never be silenced (e.g., @Monitor without SDK).
     */
    fun warn(message: String) {
        messageCollector.report(CompilerMessageSeverity.WARNING, "[Koin] $message")
    }

    /**
     * Log a user-facing message in FIR phase.
     * Adds [FIR] prefix to distinguish phase.
     *
     * The message lambda is only invoked if logging is enabled.
     */
    inline fun userFir(message: () -> String) {
        if (userLogsEnabled) {
            messageCollector.report(CompilerMessageSeverity.WARNING, "[Koin-FIR] ${message()}")
        }
    }

    /**
     * Log a debug message in FIR phase.
     * Adds [FIR] prefix to distinguish phase.
     *
     * The message lambda is only invoked if logging is enabled.
     */
    inline fun debugFir(message: () -> String) {
        if (debugLogsEnabled) {
            messageCollector.report(CompilerMessageSeverity.WARNING, "[Koin-Debug-FIR] ${message()}")
        }
    }

    /**
     * Report a compilation error.
     * This will cause compilation to fail.
     */
    fun error(message: String) {
        messageCollector.report(CompilerMessageSeverity.ERROR, "[Koin] $message")
    }

    /**
     * Report a compilation error with source location.
     * The error message will include file path and line number.
     */
    fun error(message: String, filePath: String?, line: Int, column: Int) {
        val location = if (filePath != null) {
            CompilerMessageLocation.create(filePath, line, column, null)
        } else null
        messageCollector.report(CompilerMessageSeverity.ERROR, "[Koin] $message", location)
    }
}

// Legacy alias for backward compatibility with FIR code
@Deprecated("Use KoinPluginLogger instead", ReplaceWith("KoinPluginLogger"))
object KoinPluginMessageCollector {
    fun log(message: String) {
        KoinPluginLogger.debugFir { message }
    }
}

class KoinPluginComponentRegistrar: CompilerPluginRegistrar() {
    override val supportsK2: Boolean
        get() = true

    override val pluginId: String
        get() = "io.insert-koin.compiler.plugin"

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        val userLogs = configuration.get(KoinConfigurationKeys.USER_LOGS, false)
        val debugLogs = configuration.get(KoinConfigurationKeys.DEBUG_LOGS, false)
        val unsafeDslChecks = configuration.get(KoinConfigurationKeys.UNSAFE_DSL_CHECKS, true)
        val skipDefaultValues = configuration.get(KoinConfigurationKeys.SKIP_DEFAULT_VALUES, true)
        val compileSafety = configuration.get(KoinConfigurationKeys.COMPILE_SAFETY, true)

        // IC trackers for incremental compilation support
        val lookupTracker = configuration.get(CommonConfigurationKeys.LOOKUP_TRACKER)

        // Initialize the centralized logger (includes lookupTracker for FIR-level IC recording)
        KoinPluginLogger.init(messageCollector, userLogs, debugLogs, unsafeDslChecks, skipDefaultValues, compileSafety, lookupTracker)
        val expectActualTracker = configuration.get(
            CommonConfigurationKeys.EXPECT_ACTUAL_TRACKER,
            ExpectActualTracker.DoNothing
        )
        KoinPluginLogger.debug { "IC trackers: lookupTracker=${lookupTracker?.javaClass?.simpleName ?: "NULL"}, expectActualTracker=${expectActualTracker.javaClass.simpleName}" }

        // FIR extension for generating visible declarations (module extension property)
        FirExtensionRegistrarAdapter.registerExtension(KoinPluginRegistrar())
        // IR extension for transforming function bodies
        IrGenerationExtension.registerExtension(KoinIrExtension(lookupTracker, expectActualTracker))
    }
}
