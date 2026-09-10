package org.jetbrains.kotlin.compiler.plugin.template.services

import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

private val annotationsRuntimeClasspath =
    System.getProperty("annotationsRuntime.classpath")?.split(File.pathSeparator)?.map(::File)
        ?: error("Unable to get a valid classpath from 'annotationsRuntime.classpath' property")

// Opt-in per fixture (WITH_VIEWMODEL_RUNTIME): koin-core-viewmodel + lifecycle on every test's
// classpath pushed the test JVM into OutOfMemoryError.
private val viewModelRuntimeClasspath =
    System.getProperty("viewModelRuntime.classpath")?.takeIf { it.isNotBlank() }?.split(File.pathSeparator)?.map(::File) ?: emptyList()

private fun classpathFor(module: TestModule): List<File> =
    if (KoinTestDirectives.WITH_VIEWMODEL_RUNTIME in module.directives) annotationsRuntimeClasspath + viewModelRuntimeClasspath
    else annotationsRuntimeClasspath

fun TestConfigurationBuilder.configureAnnotations() {
    useConfigurators(::PluginAnnotationsProvider)
    useCustomRuntimeClasspathProviders(::PluginAnnotationsClasspathProvider)
}

private class PluginAnnotationsProvider(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        configuration.addJvmClasspathRoots(classpathFor(module))
    }
}

private class PluginAnnotationsClasspathProvider(testServices: TestServices) : RuntimeClasspathProvider(testServices) {
    override fun runtimeClassPaths(module: TestModule) = classpathFor(module)
}