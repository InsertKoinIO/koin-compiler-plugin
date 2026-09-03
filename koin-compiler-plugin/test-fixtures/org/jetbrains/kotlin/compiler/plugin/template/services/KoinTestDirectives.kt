package org.jetbrains.kotlin.compiler.plugin.template.services

import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer

/**
 * Per-test overrides for `koinCompiler { }` options that [ExtensionRegistrarConfigurator]
 * otherwise hardcodes for every test. Add a new directive here (and thread it through the
 * configurator) when a test needs to flip an option away from its hardcoded default.
 */
object KoinTestDirectives : SimpleDirectivesContainer() {
    /** Mirrors `koinCompiler { compileSafety = false }` for the test module it's declared on. */
    val DISABLE_COMPILE_SAFETY by directive(
        description = "Disable compile-safety hint generation/validation for this test module"
    )
}
