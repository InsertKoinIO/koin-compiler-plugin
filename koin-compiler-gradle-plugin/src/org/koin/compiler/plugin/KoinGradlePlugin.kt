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
        // MANDATORY once detected (Gate 3 / 1.1.0): a plain `strictSafety = false` no longer
        // silently wins here — the whole point of the freshness net is that A2 no longer
        // provides a redundant per-module safety check, so an aggregator silently skipping
        // re-validation on an incremental rebuild is a real correctness gap, not a preference.
        // The escape hatch for a genuine detector misfire (the regex matches inside a comment or
        // string literal, not a real entry point) is [KoinGradleExtension.strictSafetyForceOff] —
        // a SEPARATE, explicit acknowledgement, not the same flag `strictSafety` already used for
        // "off by default" semantics elsewhere.
        //
        // Lazy so the file walk (and the one-time lifecycle log) happens at most once. Explicit
        // `strictSafety = true` short-circuits before the scan — detection can't change that
        // outcome, so there's no reason to pay for it (same short-circuit the old
        // `orNull ?: autoDetected.value` elvis gave for free; preserved deliberately here since an
        // unconditional scan on every explicitly-pinned module is real config-time cost on a large
        // multi-module project).
        val project = kotlinCompilation.target.project
        val explicit = extension.strictSafety.orNull
        val effective = lazy {
            if (explicit == true) {
                true
            } else {
                val detected = looksLikeAggregator(kotlinCompilation)
                val requestedOff = explicit == false
                val forceOff = extension.strictSafetyForceOff.getOrElse(false)
                when {
                    detected && requestedOff && forceOff -> {
                        project.logger.lifecycle(
                            "[Koin] strictSafety left OFF on ${project.path} despite detecting startKoin / " +
                                "@KoinApplication / koinApplication — strictSafetyForceOff = true acknowledges " +
                                "this is a detector misfire, not a real entry point in this compilation."
                        )
                        false
                    }
                    detected && requestedOff -> {
                        project.logger.warn(
                            "[Koin] strictSafety = false is being ignored on ${project.path}: this compilation " +
                                "contains a real Koin entry point (startKoin / @KoinApplication / koinApplication), " +
                                "so full-graph compile safety must run on every build. If this detection is wrong " +
                                "(e.g. the marker only appears in a comment or string literal), set " +
                                "`strictSafetyForceOff = true` in koinCompiler { } to confirm that and disable it."
                        )
                        true
                    }
                    detected -> {
                        if (!extension.strictSafety.isPresent) {
                            project.logger.lifecycle(
                                "[Koin] Auto-enabling strictSafety on ${project.path} " +
                                    "(detected startKoin / @KoinApplication / koinApplication)."
                            )
                        }
                        true
                    }
                    // Not detected: explicit is null or false here (true already handled above).
                    else -> false
                }
            }
        }

        kotlinCompilation.compileTaskProvider.configure { task ->
            task.outputs.upToDateWhen { !(effective.value && extension.compileSafety.get()) }
            task.outputs.cacheIf { !(effective.value && extension.compileSafety.get()) }
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
     * those at config time would need a real lexer. In practice this remains a heuristic; the
     * lifecycle log makes the decision visible, and `strictSafetyForceOff = true` (not a plain
     * `strictSafety = false`, which is now ignored once this detects a hit — see
     * [KoinGradlePlugin.configureStrictSafety]) is the escape for a confirmed misfire.
     */
    private val aggregatorMarkerRegex: Regex = Regex(
        "(?:(?<![A-Za-z0-9_])startKoin(?![A-Za-z0-9_]))" +
            "|(?:(?<![A-Za-z0-9_])koinApplication(?![A-Za-z0-9_]))" +
            "|(?:@KoinApplication(?![A-Za-z0-9_]))"
    )

    private fun looksLikeAggregator(kotlinCompilation: KotlinCompilation<*>): Boolean {
        return kotlinCompilation.kotlinSourceSets.any { srcSet ->
            srcSet.kotlin.files.any { file ->
                try {
                    aggregatorMarkerRegex.containsMatchIn(file.readText())
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
                SubpluginOption(OPTION_MODULE_ID, moduleId),
                SubpluginOption(OPTION_LOG_SEVERITY, extension.logSeverity.get()),
                SubpluginOption(OPTION_VERSION_CHECK_SEVERITY, extension.versionCheckSeverity.get())
            )
        }
    }
}
