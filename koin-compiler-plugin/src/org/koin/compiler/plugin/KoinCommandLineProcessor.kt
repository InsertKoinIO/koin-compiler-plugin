package org.koin.compiler.plugin

import io.insert_koin.compiler.plugin.BuildConfig
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * Configuration keys for Koin compiler plugin options.
 */
object KoinConfigurationKeys {
    val USER_LOGS: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("koin.userLogs")
    val DEBUG_LOGS: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("koin.debugLogs")
    val DSL_SAFETY_CHECKS: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("koin.dslSafetyChecks")
}

@Suppress("unused") // Used via reflection.
class KoinCommandLineProcessor : CommandLineProcessor {
    companion object {
        // Use shared constants from KoinPluginConstants
        const val OPTION_USER_LOGS = KoinPluginConstants.OPTION_USER_LOGS
        const val OPTION_DEBUG_LOGS = KoinPluginConstants.OPTION_DEBUG_LOGS
        const val OPTION_DSL_SAFETY_CHECKS = KoinPluginConstants.OPTION_DSL_SAFETY_CHECKS
    }

    override val pluginId: String = BuildConfig.KOTLIN_PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption(
            optionName = OPTION_USER_LOGS,
            valueDescription = "<true|false>",
            description = "Enable user-facing logs (component detection, DSL interceptions)",
            required = false
        ),
        CliOption(
            optionName = OPTION_DEBUG_LOGS,
            valueDescription = "<true|false>",
            description = "Enable debug logs (internal plugin processing)",
            required = false
        ),
        CliOption(
            optionName = OPTION_DSL_SAFETY_CHECKS,
            valueDescription = "<true|false>",
            description = "Enable DSL safety checks (validates create() is the only instruction in lambda)",
            required = false
        )
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            OPTION_USER_LOGS -> configuration.put(KoinConfigurationKeys.USER_LOGS, value.toBoolean())
            OPTION_DEBUG_LOGS -> configuration.put(KoinConfigurationKeys.DEBUG_LOGS, value.toBoolean())
            OPTION_DSL_SAFETY_CHECKS -> configuration.put(KoinConfigurationKeys.DSL_SAFETY_CHECKS, value.toBoolean())
            else -> error("Unexpected config option: '${option.optionName}'")
        }
    }
}
