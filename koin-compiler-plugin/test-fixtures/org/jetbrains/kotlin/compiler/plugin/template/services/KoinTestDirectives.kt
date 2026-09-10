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

    /** Mirrors `koinCompiler { directRegistration = false }`: emit the pre-1.3.0 buildSingle/bind shape. */
    val DISABLE_DIRECT_REGISTRATION by directive(
        description = "Register annotation-discovered definitions through the runtime DSL instead of directly"
    )

    /** Adds koin-core-viewmodel (and androidx.lifecycle.ViewModel) to this test's classpath. */
    val WITH_VIEWMODEL_RUNTIME by directive(
        description = "Put koin-core-viewmodel on the compile and runtime classpath of this test module"
    )
}
