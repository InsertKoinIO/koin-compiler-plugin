package org.koin.compiler.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.koin.compiler.plugin.ir.KoinIrExtension
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

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
    var dslSafetyChecksEnabled: Boolean = true
        private set

    /**
     * Initialize the logger with configuration from the compiler.
     */
    fun init(collector: MessageCollector, userLogs: Boolean, debugLogs: Boolean, dslSafetyChecks: Boolean = true) {
        messageCollector = collector
        userLogsEnabled = userLogs
        debugLogsEnabled = debugLogs
        dslSafetyChecksEnabled = dslSafetyChecks
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
        val dslSafetyChecks = configuration.get(KoinConfigurationKeys.DSL_SAFETY_CHECKS, true)

        // Initialize the centralized logger
        KoinPluginLogger.init(messageCollector, userLogs, debugLogs, dslSafetyChecks)

        // FIR extension for generating visible declarations (module extension property)
        FirExtensionRegistrarAdapter.registerExtension(KoinPluginRegistrar())
        // IR extension for transforming function bodies
        IrGenerationExtension.registerExtension(KoinIrExtension())
    }
}
