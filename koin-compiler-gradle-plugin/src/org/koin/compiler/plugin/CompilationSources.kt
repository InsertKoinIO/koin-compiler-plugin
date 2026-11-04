package org.koin.compiler.plugin

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool

/**
 * What the compilation's compile task actually compiles. AGP 9's built-in Kotlin keeps sources out of
 * the Kotlin source sets, so those are only the fallback for a task that doesn't expose its sources.
 */
internal fun compiledSources(project: Project, compilation: KotlinCompilation<*>): FileCollection {
    val sourceSetRoots = project.files(project.provider { compilation.allKotlinSourceSets.map { it.kotlin } })
    return project.files(
        compilation.compileTaskProvider.map { task -> (task as? KotlinCompileTool)?.sources ?: sourceSetRoots },
    )
}
