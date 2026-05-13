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
        const val OPTION_UNSAFE_DSL_CHECKS = "unsafeDslChecks"
        const val OPTION_SKIP_DEFAULT_VALUES = "skipDefaultValues"
        const val OPTION_COMPILE_SAFETY = "compileSafety"
        const val OPTION_AI_ASSIST = "aiAssist"
        const val OPTION_MODULE_ID = "moduleId"
    }

    private fun configureStrictSafety(
        kotlinCompilation: KotlinCompilation<*>,
        extension: KoinGradleExtension,
    ) {
        // Auto-detection of aggregator modules: when the user hasn't explicitly set
        // `strictSafety`, scan this compilation's source files for `startKoin`,
        // `koinApplication`, or `@KoinApplication`. If any is present, treat this as
        // the aggregator that needs full-graph validation on every build (because DSL
        // lambda bodies inside transitive module dependencies are not part of any
        // declaration's ABI and Kotlin's incremental compilation can't see changes
        // to them). Lazy so the file walk happens at most once per Gradle invocation.
        val project = kotlinCompilation.target.project
        val autoDetected = lazy {
            looksLikeAggregator(kotlinCompilation).also { detected ->
                if (detected && !extension.strictSafety.isPresent) {
                    project.logger.lifecycle(
                        "[Koin] Auto-enabling strictSafety on ${project.path} " +
                            "(detected startKoin / @KoinApplication / koinApplication). " +
                            "Set `strictSafety = false` in koinCompiler { } to override."
                    )
                }
            }
        }

        kotlinCompilation.compileTaskProvider.configure { task ->
            task.outputs.upToDateWhen {
                val effective = extension.strictSafety.orNull ?: autoDetected.value
                !(effective && extension.compileSafety.get())
            }
            task.outputs.cacheIf {
                val effective = extension.strictSafety.orNull ?: autoDetected.value
                !(effective && extension.compileSafety.get())
            }
        }
    }

    private fun looksLikeAggregator(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val markers = listOf("startKoin", "koinApplication", "@KoinApplication")
        return kotlinCompilation.kotlinSourceSets.any { srcSet ->
            srcSet.kotlin.files.any { file ->
                try {
                    val text = file.readText()
                    markers.any { it in text }
                } catch (_: Throwable) {
                    false
                }
            }
        }
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

        configureStrictSafety(kotlinCompilation, extension)

        // Use Gradle project.path (e.g. ":featureA:ui") as a stable, Gradle-module-unique
        // moduleId so synthetic hint files in org.koin.plugin.hints get module-disambiguated
        // and don't collide at dex merge. See KoinPluginConstants.OPTION_MODULE_ID.
        val moduleId = project.path

        return project.provider {
            listOf(
                SubpluginOption(OPTION_USER_LOGS, extension.userLogs.get().toString()),
                SubpluginOption(OPTION_DEBUG_LOGS, extension.debugLogs.get().toString()),
                SubpluginOption(OPTION_UNSAFE_DSL_CHECKS, extension.unsafeDslChecks.get().toString()),
                SubpluginOption(OPTION_SKIP_DEFAULT_VALUES, extension.skipDefaultValues.get().toString()),
                SubpluginOption(OPTION_COMPILE_SAFETY, extension.compileSafety.get().toString()),
                SubpluginOption(OPTION_AI_ASSIST, extension.aiAssist.get().toString()),
                SubpluginOption(OPTION_MODULE_ID, moduleId)
            )
        }
    }
}
