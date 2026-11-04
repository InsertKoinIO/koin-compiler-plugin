// KLIB hint-visibility repro (#105, #106, #113): hints generated in a synthetic IR file never reach
// the KLIB on Kotlin 2.4.20+ (KT-79565), so a downstream entry point sees an empty graph on wasmJs / iosArm64.
// jvm is the control: same sources, hints are real class files there.
pluginManagement {
    val kotlinVersion: String = (settings.extra.properties["kotlinVersion"] as? String) ?: "2.4.20"
    val koinPluginVersion: String = (settings.extra.properties["koinPluginVersion"] as? String) ?: "1.2.1"
    plugins {
        kotlin("multiplatform") version kotlinVersion
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
    }
}

rootProject.name = "app-kmp-klib-hints"
include(":lib-config", ":lib-plain", ":lib-dsl", ":app", ":app-falsify")

// -Pstale=true: :lib-stale is pinned to plugin 1.2.1 (its KLIB carries no scan hints), consumed by :app-stale.
if (providers.gradleProperty("stale").orNull == "true") include(":lib-stale", ":app-stale")

// Every IR-built hint kind, crossed once; ./verify-wide.sh checks each KLIB target reports exactly what jvm reports.
include(":lib-wide", ":app-wide")
