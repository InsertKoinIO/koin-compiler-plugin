package org.koin.compiler.plugin

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

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
        const val OPTION_LOG_SEVERITY = "logSeverity"
        const val OPTION_VERSION_CHECK_SEVERITY = "versionCheckSeverity"
    }

    private fun configureStrictSafety(
        kotlinCompilation: KotlinCompilation<*>,
        extension: KoinGradleExtension,
    ) {
        // Auto-detection of aggregator modules: scan this compilation's source files for
        // `startKoin`, `koinApplication`, or `@KoinApplication`. If any is present, this
        // compilation owns a real Koin entry point, whose full-graph validation must run on
        // every build (DSL lambda bodies inside transitive module dependencies are not part of
        // any declaration's ABI, so Kotlin's incremental compilation can't see changes to them).
        //
        // An explicit `strictSafety = false` wins (1.2.2, #120), with a warning when an entry
        // point is detected: forcing a full recompile of the aggregator every build cost a lot on
        // large app modules (GH #120) and still didn't guarantee the check under IC (GH #117).
        // Clean and CI builds always validate.
        //
        // Decided when Gradle evaluates the specs, at execution time, once per build: the source
        // read is then never a configuration-cache input. A Lazy captured here was forced when the
        // configuration cache serialized the specs, so editing any Kotlin source invalidated the
        // entry. Explicit `strictSafety = true` and `compileSafety = false` skip the scan.
        val project = kotlinCompilation.target.project

        @Suppress("DEPRECATION") // strictSafetyForceOff is read only to warn that it's no longer needed
        val decision = StrictSafetyDecision(
            projectPath = project.path,
            strictSafety = extension.strictSafety,
            strictSafetyForceOff = extension.strictSafetyForceOff,
            compileSafety = extension.compileSafety,
            sources = strictSafetySources(project, kotlinCompilation),
        )

        kotlinCompilation.compileTaskProvider.configure { task ->
            task.outputs.upToDateWhen { !decision.isActive() }
            task.outputs.cacheIf { !decision.isActive() }
        }
    }

    /**
     * A plain JVM or Android compilation: what its compile task compiles, `src/<set>/java` and AGP 9's
     * built-in Kotlin included. A KMP compilation: its own source sets only, not the shared ones it
     * inherits (an entry point in `commonMain` doesn't make every target an aggregator).
     */
    private fun strictSafetySources(project: Project, compilation: KotlinCompilation<*>): FileCollection {
        val compiled = compiledSources(project, compilation)
        val ownSourceSets = project.files(project.provider { compilation.kotlinSourceSets.map { it.kotlin } })
        return project.files(
            project.provider {
                val inheritsSourceSets = compilation.allKotlinSourceSets.size > compilation.kotlinSourceSets.size
                if (inheritsSourceSets) ownSourceSets else compiled
            },
        )
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
        registerHiddenDependencyCheck(project, extension, kotlinCompilation)

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
                SubpluginOption(OPTION_MODULE_ID, moduleId),
                SubpluginOption(OPTION_LOG_SEVERITY, extension.logSeverity.get()),
                SubpluginOption(OPTION_VERSION_CHECK_SEVERITY, extension.versionCheckSeverity.get())
            )
        }
    }
}

/**
 * Identifier-boundary regex for the three aggregator markers. A plain substring check
 * (`"startKoin" in text`) flips strictSafety on for anything containing the token —
 * `restartKoinIfNeeded`, `myStartKoinHelper`, comments, string literals — which forces
 * `compileKotlin` to re-run every build for modules that aren't actually aggregators.
 *
 * Boundary rules:
 *  - `startKoin` / `koinApplication` must be preceded by something that isn't a Kotlin
 *    identifier part (`[A-Za-z0-9_]`) so `restartKoin` doesn't match `startKoin`, and
 *    `myStartKoin` doesn't match either. Trailing side is similar — `startKoinInternal`
 *    is not the call we care about.
 *  - `@KoinApplication` only needs the trailing boundary; the `@` already anchors the
 *    leading side.
 *
 * We still match identifiers that appear in comments and string literals — stripping
 * those would need a real lexer. In practice this remains a heuristic; the
 * lifecycle log makes the decision visible, and `strictSafety = false` turns it off.
 */
private val aggregatorMarkerRegex: Regex = Regex(
    "(?:(?<![A-Za-z0-9_])startKoin(?![A-Za-z0-9_]))" +
        "|(?:(?<![A-Za-z0-9_])koinApplication(?![A-Za-z0-9_]))" +
        "|(?:@KoinApplication(?![A-Za-z0-9_]))",
)

internal fun containsAggregatorMarker(file: File): Boolean =
    runCatching { aggregatorMarkerRegex.containsMatchIn(file.readText()) }.getOrDefault(false)

/**
 * Whether strictSafety is on for one compilation. Evaluated lazily by the compile task's
 * up-to-date and cache specs, once per build, so the source scan never runs at configuration time.
 *
 * Policy: an explicit value wins (`false` warns when an entry point is detected); unset, detection
 * decides. Off, with no scan and no log, when compileSafety is.
 */
internal class StrictSafetyDecision(
    private val projectPath: String,
    private val strictSafety: Provider<Boolean>,
    private val strictSafetyForceOff: Provider<Boolean>,
    private val compileSafety: Provider<Boolean>,
    private val sources: FileCollection,
) {
    @Volatile
    private var active: Boolean? = null

    fun isActive(): Boolean = active ?: decide().also { active = it }

    private fun decide(): Boolean {
        val explicit = strictSafety.orNull
        return when {
            !compileSafety.getOrElse(true) -> false
            explicit == true -> true
            sources.asFileTree.files.none(::containsAggregatorMarker) -> false
            explicit == false -> {
                if (strictSafetyForceOff.getOrElse(false)) {
                    logger.warn(
                        "[Koin] strictSafetyForceOff is deprecated on $projectPath: strictSafety = false " +
                            "is honored on its own now. Remove strictSafetyForceOff.",
                    )
                }
                logger.warn(
                    "[Koin] strictSafety = false on $projectPath, which has a Koin entry point: incremental " +
                        "builds may miss graph changes. Clean and CI builds still validate.",
                )
                false
            }
            else -> {
                logger.lifecycle(
                    "[Koin] Auto-enabling strictSafety on $projectPath " +
                        "(detected startKoin / @KoinApplication / koinApplication).",
                )
                true
            }
        }
    }

    private companion object {
        val logger: Logger = Logging.getLogger(KoinGradlePlugin::class.java)
    }
}
