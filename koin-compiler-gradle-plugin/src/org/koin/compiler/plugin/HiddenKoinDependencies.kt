package org.koin.compiler.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.BuildIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.io.File
import java.io.Serializable
import java.util.concurrent.Callable
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/*
 * Compile safety validates the graph where Koin starts, from what that compilation can see. A module
 * that contributes definitions only at runtime (an `implementation` dependency of a dependency) is
 * invisible there, so its definitions go unchecked. Rather than re-describing such modules through
 * the ones in between, the rule is the one Metro uses: the module that starts Koin depends directly
 * on every module contributing definitions. This check finds the ones it doesn't.
 *
 * Mechanism adapted from Metro's hidden-dependency check (https://github.com/ZacSweers/metro,
 * Apache 2.0): compare the compile and runtime resolution graphs by component identity, and scan
 * only the runtime-only components for hints. On by default here, as a warning.
 */

private const val HINTS_PATH = "org/koin/plugin/hints/"
private const val AGP_VERSION_ATTRIBUTE = "com.android.build.api.attributes.AgpVersionAttr"
private val CHECKED_PLATFORMS = setOf(KotlinPlatformType.jvm, KotlinPlatformType.androidJvm)

internal enum class HiddenDependencySeverity {
    WARNING,
    ERROR,
    NONE,
    ;

    companion object {
        fun parse(value: String): HiddenDependencySeverity =
            values().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw GradleException(
                    "[Koin] koinCompiler { hiddenDependencyCheck = \"$value\" } is not supported: " +
                        "use \"warning\", \"error\" or \"none\".",
                )
    }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
internal fun registerHiddenDependencyCheck(
    project: Project,
    extension: KoinGradleExtension,
    compilation: KotlinCompilation<*>,
) {
    // Only JVM and Android: KLIB compilations already see transitive dependencies.
    if (compilation.platformType !in CHECKED_PLATFORMS) return
    val runtimeName = compilation.runtimeDependencyConfigurationName ?: return
    val segments = listOf(compilation.target.name, compilation.name).filter(String::isNotBlank)
    val qualifier = segments.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    val runtimeConfiguration = project.configurations.named(runtimeName)
    val compileConfiguration = project.configurations.named(compilation.compileDependencyConfigurationName)
    val compileIds = project.objects.setProperty(String::class.java).apply {
        set(resolvedGraph(compileConfiguration).map { it.paths.keys })
        finalizeValueOnRead()
    }
    val runtimeGraph = project.objects.property(KoinDependencyGraph::class.java).apply {
        set(resolvedGraph(runtimeConfiguration))
        finalizeValueOnRead()
    }

    val checkTask = project.tasks.register(
        "check${qualifier}KoinHiddenDependencies",
        CheckHiddenKoinDependenciesTask::class.java,
    ) { task ->
        val associatedOutputs = project.files(compilation.allAssociatedCompilations.map { it.output.classesDirs })
        val isAndroid = compilation.platformType == KotlinPlatformType.androidJvm
        for (view in runtimeOnlyViews(runtimeConfiguration, compileIds, runtimeGraph, isAndroid)) {
            val artifacts = view.artifacts
            task.runtimeArtifacts.addAll(
                artifacts.resolvedArtifacts.map { resolved ->
                    val visibleFiles = associatedOutputs.files
                    resolved.filter { it.file !in visibleFiles }.map { artifact ->
                        HiddenKoinArtifact(componentKey(artifact.id.componentIdentifier), artifact.file)
                    }
                },
            )
            task.runtimeFiles.from(artifacts.artifactFiles.minus(associatedOutputs))
        }
        val compilationPath = segments.joinToString("/")
        task.description = "Checks for modules contributing Koin definitions only at runtime, for $compilationPath"
        task.consumer.set("${project.path} ($compilationPath)")
        task.compileComponentIds.set(compileIds)
        task.runtimeDependencyPaths.set(runtimeGraph.map { it.paths })
        task.runtimeDependencyNotations.set(runtimeGraph.map { it.notations })
        task.sources.from(compiledSources(project, compilation))
        task.severity.set(extension.hiddenDependencyCheck.map { HiddenDependencySeverity.parse(it).name })
        val report = "reports/koin/$compilationPath/hidden-dependencies.txt"
        task.reportFile.convention(project.layout.buildDirectory.file(report))
    }

    compilation.compileTaskProvider.configure { task ->
        task.finalizedBy(
            Callable {
                val severity = HiddenDependencySeverity.parse(extension.hiddenDependencyCheck.get())
                val enabled = extension.compileSafety.get() && severity != HiddenDependencySeverity.NONE
                if (enabled) listOf(checkTask) else emptyList()
            },
        )
    }
}

/** Artifacts of runtime-only components; AGP's outputs need an artifact type to pick one. */
private fun runtimeOnlyViews(
    runtimeConfiguration: NamedDomainObjectProvider<Configuration>,
    compileIds: SetProperty<String>,
    runtimeGraph: Property<KoinDependencyGraph>,
    isAndroid: Boolean,
): List<ArtifactView> {
    val incoming = runtimeConfiguration.get().incoming
    // The filters capture only the two properties: the configuration cache stores them.
    return buildList {
        add(
            incoming.artifactView { view ->
                view.componentFilter { isHidden(it, compileIds, runtimeGraph, false) }
            },
        )
        if (isAndroid) {
            add(
                incoming.artifactView { view ->
                    view.attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
                    view.componentFilter { isHidden(it, compileIds, runtimeGraph, true) }
                },
            )
        }
    }
}

/** File dependencies aren't graph components: their identity can't be compared, so they're skipped. */
private fun isHidden(
    id: ComponentIdentifier,
    compileIds: Provider<Set<String>>,
    runtimeGraph: Provider<KoinDependencyGraph>,
    android: Boolean,
): Boolean {
    if (id !is ModuleComponentIdentifier && id !is ProjectComponentIdentifier) return false
    val key = componentKey(id)
    return key !in compileIds.get() && (key in runtimeGraph.get().androidComponents) == android
}

/** A runtime artifact's identity and file, as plain values the configuration cache can keep. */
internal data class HiddenKoinArtifact(
    @get:Input val componentId: String,
    @get:Internal val file: File,
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}

internal abstract class CheckHiddenKoinDependenciesTask : DefaultTask() {
    @get:Input abstract val consumer: Property<String>

    @get:Input abstract val compileComponentIds: SetProperty<String>

    @get:Nested abstract val runtimeArtifacts: ListProperty<HiddenKoinArtifact>

    /** Keeps the producers' task dependencies and classpath fingerprints. */
    @get:Classpath abstract val runtimeFiles: ConfigurableFileCollection

    @get:Input abstract val runtimeDependencyPaths: MapProperty<String, String>

    @get:Input abstract val runtimeDependencyNotations: MapProperty<String, String>

    /** Read at execution only: whether this compilation starts Koin. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input abstract val severity: Property<String>

    @get:OutputFile abstract val reportFile: RegularFileProperty

    init {
        group = "verification"
        runtimeDependencyPaths.convention(emptyMap())
        runtimeDependencyNotations.convention(emptyMap())
        // A finding re-runs every build, so the warning doesn't vanish once printed.
        outputs.upToDateWhen { task ->
            (task as CheckHiddenKoinDependenciesTask).reportFile.get().asFile.let { it.isFile && it.length() == 0L }
        }
    }

    @TaskAction
    fun check() {
        val output = reportFile.get().asFile.apply { parentFile.mkdirs() }
        val failsBuild = HiddenDependencySeverity.valueOf(severity.get()) == HiddenDependencySeverity.ERROR
        val startsKoin = sources.asFileTree.files.any { it.extension == "kt" && containsAggregatorMarker(it) }
        val compileIds = compileComponentIds.get()
        val hidden = if (!startsKoin) {
            emptyList()
        } else {
            runtimeArtifacts.get()
                .filter { it.componentId !in compileIds }
                .groupBy { it.componentId }
                .filterValues { artifacts -> artifacts.any { containsKoinHints(it.file) } }
                .keys
                .sorted()
        }
        if (hidden.isEmpty()) {
            output.writeText("")
            return
        }
        val report = hiddenDependencyReport(hidden, failsBuild)
        output.writeText(report)
        if (failsBuild) throw GradleException(report)
        logger.warn(report)
    }

    private fun hiddenDependencyReport(hidden: List<String>, failsBuild: Boolean): String {
        val paths = runtimeDependencyPaths.get()
        val notations = runtimeDependencyNotations.get()
        return buildString {
            appendLine(
                "[Koin] ${consumer.get()} starts Koin, but these modules contribute to its Koin graph without " +
                    "being on its compile classpath, so compile safety can't fully check them.",
            )
            for (componentId in hidden) {
                val via = paths[componentId]?.let { "  (via $it)" }.orEmpty()
                appendLine("  - ${notations[componentId] ?: componentId}$via")
            }
            appendLine(
                "  Put each on this compilation's compile classpath: a direct implementation dependency " +
                    "(not runtimeOnly), or api(...) on the way.",
            )
            append(
                if (failsBuild) {
                    "  hiddenDependencyCheck = \"warning\" in koinCompiler { } reports without failing; " +
                        "\"none\" turns this off."
                } else {
                    "  hiddenDependencyCheck = \"error\" in koinCompiler { } fails the build instead; " +
                        "\"none\" turns this off."
                },
            )
        }
    }
}

internal fun containsKoinHints(file: File): Boolean = runCatching {
    when {
        file.isDirectory -> File(file, HINTS_PATH).listFiles()?.any { it.name.endsWith(".class") } == true
        file.isFile && file.extension == "jar" -> ZipFile(file).use { jar ->
            jar.entries().asSequence().any { it.name.isHintClass() }
        }
        // An AAR from a repository: its classes sit in nested jars.
        file.isFile && file.extension == "aar" -> ZipFile(file).use { aar ->
            aar.entries().asSequence()
                .filter { it.name == "classes.jar" || (it.name.startsWith("libs/") && it.name.endsWith(".jar")) }
                .any { nested ->
                    ZipInputStream(aar.getInputStream(nested)).use { jar ->
                        generateSequence { jar.nextEntry }.any { it.name.isHintClass() }
                    }
                }
        }
        else -> false
    }
}.getOrDefault(false)

private fun String.isHintClass(): Boolean = startsWith(HINTS_PATH) && endsWith(".class")

/** The same logical module across compile and runtime; a different resolved version doesn't hide it. */
private fun componentKey(id: ComponentIdentifier): String = when (id) {
    is ModuleComponentIdentifier -> "module:${id.group}:${id.module}"
    is ProjectComponentIdentifier -> "project:${id.displayName}"
    else -> id.displayName
}

private data class KoinDependencyGraph(
    val paths: Map<String, String>,
    val notations: Map<String, String>,
    val androidComponents: Set<String>,
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}

private fun resolvedGraph(configuration: NamedDomainObjectProvider<Configuration>): Provider<KoinDependencyGraph> =
    configuration.flatMap { it.incoming.resolutionResult.rootComponent }.map(::dependencyGraph)

/**
 * One shortest path per component, for the report, and how to declare it: `project(...)` within this
 * build, coordinates for an included build or a repository. Android variants flagged by AGP's attribute.
 */
private fun dependencyGraph(root: ResolvedComponentResult): KoinDependencyGraph {
    val rootBuild = (root.id as? ProjectComponentIdentifier)?.build
    val paths = mutableMapOf<String, String>()
    val notations = mutableMapOf<String, String>()
    val androidComponents = mutableSetOf<String>()
    val pending = ArrayDeque<Pair<ResolvedComponentResult, String>>()
    pending.add(root to root.id.displayName)
    while (pending.isNotEmpty()) {
        val (component, path) = pending.removeFirst()
        val key = componentKey(component.id)
        if (paths.putIfAbsent(key, path) != null) continue
        notations[key] = dependencyNotation(component, rootBuild)
        for (dependency in component.dependencies) {
            if (dependency !is ResolvedDependencyResult || dependency.isConstraint) continue
            val selected = dependency.selected
            if (dependency.resolvedVariant.attributes.keySet().any { it.name == AGP_VERSION_ATTRIBUTE }) {
                androidComponents += componentKey(selected.id)
            }
            pending.add(selected to "$path -> ${selected.id.displayName}")
        }
    }
    return KoinDependencyGraph(paths, notations, androidComponents)
}

private fun dependencyNotation(component: ResolvedComponentResult, rootBuild: BuildIdentifier?): String {
    val id = component.id
    val coordinates = component.moduleVersion?.let { "\"${it.group}:${it.name}:${it.version}\"" }
    return when {
        id is ProjectComponentIdentifier && id.build == rootBuild -> "project(\"${id.projectPath}\")"
        id is ModuleComponentIdentifier -> "\"${id.group}:${id.module}:${id.version}\""
        else -> coordinates ?: id.displayName
    }
}
