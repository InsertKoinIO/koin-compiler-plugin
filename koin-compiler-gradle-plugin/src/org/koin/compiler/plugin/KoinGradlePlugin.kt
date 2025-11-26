package org.koin.compiler.plugin

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

@Suppress("unused") // Used via reflection.
class KoinGradlePlugin : KotlinCompilerPluginSupportPlugin {

    companion object {
        // Use shared constants - these are inlined at compile time for Gradle plugin
        // Note: These are duplicated rather than imported to avoid a dependency from
        // koin-compiler-gradle-plugin on koin-compiler-plugin at Gradle configuration time
        const val OPTION_USER_LOGS = "userLogs"
        const val OPTION_DEBUG_LOGS = "debugLogs"
        const val OPTION_DSL_SAFETY_CHECKS = "dslSafetyChecks"
    }

    override fun apply(target: Project) {
        target.extensions.create("koinCompiler", KoinGradleExtension::class.java)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = io.insert_koin.compiler.plugin.BuildConfig.KOTLIN_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = io.insert_koin.compiler.plugin.BuildConfig.KOTLIN_PLUGIN_GROUP,
        artifactId = io.insert_koin.compiler.plugin.BuildConfig.KOTLIN_PLUGIN_NAME,
        version = io.insert_koin.compiler.plugin.BuildConfig.KOTLIN_PLUGIN_VERSION,
    )

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val extension = project.extensions.getByType(KoinGradleExtension::class.java)

        return project.provider {
            listOf(
                SubpluginOption(OPTION_USER_LOGS, extension.userLogs.get().toString()),
                SubpluginOption(OPTION_DEBUG_LOGS, extension.debugLogs.get().toString()),
                SubpluginOption(OPTION_DSL_SAFETY_CHECKS, extension.dslSafetyChecks.get().toString())
            )
        }
    }
}
