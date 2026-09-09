// KTZ-4829 startup benchmark: Metro-shaped annotation workload compiled with the Koin compiler
// plugin, measured with JMH. Standalone build; run from the repo root with
//   ./gradlew -p perf-apps/startup-bench jmh -PkoinVersion=4.2.2 -PdirectRegistration=true
pluginManagement {
    val kotlinVersion: String = (settings.extra.properties["kotlinVersion"] as? String) ?: "2.4.0"
    val kcpVersion: String = (settings.extra.properties["kcpVersion"] as? String) ?: "1.3.0"
    plugins {
        kotlin("jvm") version kotlinVersion
        id("io.insert-koin.compiler.plugin") version kcpVersion
        id("me.champeau.jmh") version "0.7.3"
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
rootProject.name = "startup-bench"
