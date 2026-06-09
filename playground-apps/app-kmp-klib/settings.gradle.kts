// Multiplatform KLIB verification app — proves the injectedparams_* hint is emitted
// once so KLIB serialization (wasmJs/native) succeeds. JVM tolerates duplicates;
// this target is where compiler#40/#44 actually failed.
pluginManagement {
    val kotlinVersion: String = (settings.extra.properties["kotlinVersion"] as? String) ?: "2.3.20"
    plugins {
        kotlin("multiplatform") version kotlinVersion
        id("io.insert-koin.compiler.plugin") version "1.0.1"
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

rootProject.name = "app-kmp-klib"
