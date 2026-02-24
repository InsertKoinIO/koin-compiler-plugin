package org.jetbrains.kotlin.compiler.plugin.template.services

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.KoinPluginRegistrar
import org.koin.compiler.plugin.ir.KoinIrExtension
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

/**
 * Thread-local holder for captured compiler error messages during tests.
 * Uses ThreadLocal to avoid cross-test contamination when tests run in parallel.
 * Cleared before each test run by [AbstractJvmErrorMessageTest].
 */
object CapturedErrors {
    private val threadLocalErrors = ThreadLocal.withInitial { mutableListOf<String>() }

    val errors: MutableList<String> get() = threadLocalErrors.get()

    fun clear() { threadLocalErrors.get().clear() }
}

/**
 * Wraps a [MessageCollector] to capture ERROR-severity messages
 * for assertion in error message golden file tests.
 */
class CapturingMessageCollector(private val delegate: MessageCollector) : MessageCollector {
    override fun clear() = delegate.clear()
    override fun hasErrors(): Boolean = delegate.hasErrors()

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
        if (severity == CompilerMessageSeverity.ERROR) {
            CapturedErrors.errors.add(message)
        }
        delegate.report(severity, message, location)
    }
}

fun TestConfigurationBuilder.configurePlugin() {
    useConfigurators(::ExtensionRegistrarConfigurator)
    configureAnnotations()
}

private class ExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration
    ) {
        val rawCollector = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        val messageCollector = CapturingMessageCollector(rawCollector)
        // Initialize the logger for tests (enable both user and debug logs)
        KoinPluginLogger.init(messageCollector, userLogs = true, debugLogs = true, safetyChecks = true)
        FirExtensionRegistrarAdapter.registerExtension(KoinPluginRegistrar())
        IrGenerationExtension.registerExtension(KoinIrExtension())
    }
}
