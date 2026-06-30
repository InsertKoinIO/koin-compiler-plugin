package org.jetbrains.kotlin.compiler.plugin.template.runners

import org.jetbrains.kotlin.compiler.plugin.template.services.configurePlugin
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.test.FirParser
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.runners.codegen.AbstractFirBlackBoxCodegenTestBase
import org.jetbrains.kotlin.test.services.EnvironmentBasedStandardLibrariesPathProvider
import org.jetbrains.kotlin.test.services.KotlinStandardLibrariesPathProvider

open class AbstractJvmBoxTest : AbstractFirBlackBoxCodegenTestBase(FirParser.LightTree) {
    override fun createKotlinStandardLibrariesPathProvider(): KotlinStandardLibrariesPathProvider {
        return EnvironmentBasedStandardLibrariesPathProvider
    }

    override fun configure(builder: TestConfigurationBuilder) = with(builder) {
        super.configure(this)
        /*
         * Containers of different directives, which can be used in tests:
         * - ModuleStructureDirectives
         * - LanguageSettingsDirectives
         * - DiagnosticsDirectives
         * - FirDiagnosticsDirectives
         * - CodegenTestDirectives
         * - JvmEnvironmentConfigurationDirectives
         *
         * All of them are located in `org.jetbrains.kotlin.test.directives` package
         */
        defaultDirectives {
            +CodegenTestDirectives.DUMP_IR
            +FirDiagnosticsDirectives.FIR_DUMP
            +JvmEnvironmentConfigurationDirectives.FULL_JDK

            // JVM 11 so test sources can inline from JVM-11 deps (e.g. androidx
            // lifecycle ViewModel pulled in by koin-core-viewmodel for issue #49).
            JvmEnvironmentConfigurationDirectives.JVM_TARGET with JvmTarget.JVM_11

            +CodegenTestDirectives.IGNORE_DEXING // Avoids loading R8 from the classpath.
        }

        configurePlugin()
    }
}
