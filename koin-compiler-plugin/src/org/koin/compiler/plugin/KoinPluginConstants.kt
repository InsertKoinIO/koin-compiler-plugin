package org.koin.compiler.plugin

/**
 * Shared constants for the Koin compiler plugin.
 *
 * Centralizes option keys, definition type names, and other constants
 * used across both the compiler plugin and Gradle plugin.
 */
object KoinPluginConstants {

    // ================================================================================
    // Plugin Options - These names must match between compiler and Gradle plugins
    // ================================================================================

    /** Option to enable user-facing logs (component detection, DSL interceptions). */
    const val OPTION_USER_LOGS = "userLogs"

    /** Option to enable debug logs (internal plugin processing). */
    const val OPTION_DEBUG_LOGS = "debugLogs"

    /** Option to enable DSL safety checks (validates create() is the only instruction in lambda). */
    const val OPTION_DSL_SAFETY_CHECKS = "dslSafetyChecks"

    // ================================================================================
    // Definition Types - Used for hint functions and logging
    // ================================================================================

    /** Definition type for single/singleton definitions. */
    const val DEF_TYPE_SINGLE = "single"

    /** Definition type for factory definitions. */
    const val DEF_TYPE_FACTORY = "factory"

    /** Definition type for scoped definitions. */
    const val DEF_TYPE_SCOPED = "scoped"

    /** Definition type for viewModel definitions. */
    const val DEF_TYPE_VIEWMODEL = "viewmodel"

    /** Definition type for worker definitions. */
    const val DEF_TYPE_WORKER = "worker"

    /** All supported definition types. */
    val ALL_DEFINITION_TYPES = listOf(
        DEF_TYPE_SINGLE,
        DEF_TYPE_FACTORY,
        DEF_TYPE_SCOPED,
        DEF_TYPE_VIEWMODEL,
        DEF_TYPE_WORKER
    )

    // ================================================================================
    // Hint Functions - For cross-module discovery
    // ================================================================================

    /** Package where hint functions are generated for cross-module discovery. */
    const val HINTS_PACKAGE = "org.koin.plugin.hints"

    /** Prefix for configuration hint functions (e.g., configuration_default). */
    const val HINT_FUNCTION_PREFIX = "configuration_"

    /** Prefix for definition hint functions (e.g., definition_single). */
    const val DEFINITION_HINT_PREFIX = "definition_"

    /** Default label for @Configuration modules. */
    const val DEFAULT_LABEL = "default"

    // ================================================================================
    // Generated Function Names
    // ================================================================================

    /** Name of the generated module extension function. */
    const val MODULE_FUNCTION_NAME = "module"
}
