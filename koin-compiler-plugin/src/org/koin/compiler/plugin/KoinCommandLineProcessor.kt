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
    val UNSAFE_DSL_CHECKS: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("koin.unsafeDslChecks")
    val SKIP_DEFAULT_VALUES: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("koin.skipDefaultValues")
    val COMPILE_SAFETY: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("koin.compileSafety")
    val AI_ASSIST: CompilerConfigurationKey<Boolean> = CompilerConfigurationKey.create("koin.aiAssist")
    val MODULE_ID: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("koin.moduleId")
    val LOG_SEVERITY: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("koin.logSeverity")
    val VERSION_CHECK_SEVERITY: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("koin.versionCheckSeverity")
}

@Suppress("unused") // Used via reflection.
class KoinCommandLineProcessor : CommandLineProcessor {
    companion object {
        // Use shared constants from KoinPluginConstants
        const val OPTION_USER_LOGS = KoinPluginConstants.OPTION_USER_LOGS
        const val OPTION_DEBUG_LOGS = KoinPluginConstants.OPTION_DEBUG_LOGS
        const val OPTION_UNSAFE_DSL_CHECKS = KoinPluginConstants.OPTION_UNSAFE_DSL_CHECKS
        const val OPTION_SKIP_DEFAULT_VALUES = KoinPluginConstants.OPTION_SKIP_DEFAULT_VALUES
        const val OPTION_COMPILE_SAFETY = KoinPluginConstants.OPTION_COMPILE_SAFETY
        const val OPTION_AI_ASSIST = KoinPluginConstants.OPTION_AI_ASSIST
        const val OPTION_MODULE_ID = KoinPluginConstants.OPTION_MODULE_ID
        const val OPTION_LOG_SEVERITY = KoinPluginConstants.OPTION_LOG_SEVERITY
        const val OPTION_VERSION_CHECK_SEVERITY = KoinPluginConstants.OPTION_VERSION_CHECK_SEVERITY
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
            optionName = OPTION_UNSAFE_DSL_CHECKS,
            valueDescription = "<true|false>",
            description = "Enable unsafe DSL checks (validates create() is the only instruction in lambda)",
            required = false
        ),
        CliOption(
            optionName = OPTION_SKIP_DEFAULT_VALUES,
            valueDescription = "<true|false>",
            description = "Skip injection for parameters with default values (use Kotlin defaults instead)",
            required = false
        ),
        CliOption(
            optionName = OPTION_COMPILE_SAFETY,
            valueDescription = "<true|false>",
            description = "Enable compile-time dependency safety checks (validates all required dependencies are provided)",
            required = false
        ),
        CliOption(
            optionName = OPTION_AI_ASSIST,
            valueDescription = "<true|false>",
            description = "Append an AI-assist hint pointing to Kotzilla MCP at the end of each Koin error message",
            required = false
        ),
        CliOption(
            optionName = OPTION_MODULE_ID,
            valueDescription = "<gradle-project-path>",
            description = "Stable Gradle-module-unique identifier (typically project.path). Disambiguates synthetic hint files across modules.",
            required = false
        ),
        CliOption(
            optionName = OPTION_LOG_SEVERITY,
            valueDescription = "<warning|info>",
            description = "Severity of the plugin's informational output (user/debug logs, @Monitor summaries). 'info' is safe under allWarningsAsErrors.",
            required = false
        ),
        CliOption(
            optionName = OPTION_VERSION_CHECK_SEVERITY,
            valueDescription = "<warning|info>",
            description = "Severity of the Kotlin-version-compatibility warning, independent of logSeverity. 'info' is safe under allWarningsAsErrors.",
            required = false
        )
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            OPTION_USER_LOGS -> configuration.put(KoinConfigurationKeys.USER_LOGS, value.toBoolean())
            OPTION_DEBUG_LOGS -> configuration.put(KoinConfigurationKeys.DEBUG_LOGS, value.toBoolean())
            OPTION_UNSAFE_DSL_CHECKS -> configuration.put(KoinConfigurationKeys.UNSAFE_DSL_CHECKS, value.toBoolean())
            OPTION_SKIP_DEFAULT_VALUES -> configuration.put(KoinConfigurationKeys.SKIP_DEFAULT_VALUES, value.toBoolean())
            OPTION_COMPILE_SAFETY -> configuration.put(KoinConfigurationKeys.COMPILE_SAFETY, value.toBoolean())
            OPTION_AI_ASSIST -> configuration.put(KoinConfigurationKeys.AI_ASSIST, value.toBoolean())
            OPTION_MODULE_ID -> configuration.put(KoinConfigurationKeys.MODULE_ID, value)
            OPTION_LOG_SEVERITY -> configuration.put(KoinConfigurationKeys.LOG_SEVERITY, value)
            OPTION_VERSION_CHECK_SEVERITY -> configuration.put(KoinConfigurationKeys.VERSION_CHECK_SEVERITY, value)
            else -> error("Unexpected config option: '${option.optionName}'")
        }
    }
}
