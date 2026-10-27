pluginManagement {
    // Always the plugin built from this checkout (./install.sh); -PkoinPluginVersion=… overrides.
    val koinPluginVersion: String = (settings.extra.properties["koinPluginVersion"] as? String)
        ?: java.util.Properties().apply { file("../gradle.properties").inputStream().use(::load) }
            .getProperty("pluginVersion")
    plugins {
        id("io.insert-koin.compiler.plugin") version koinPluginVersion
    }
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }
}

rootProject.name = "koin-compiler-samples"

include("sample-feature-module")
include("sample-app")
