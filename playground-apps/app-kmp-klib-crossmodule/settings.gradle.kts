// Cross-module @ComponentScan KLIB regression sample.
//
// `:feature` declares @Single classes; `:app` has a @Module @ComponentScan covering the
// feature package and depends on :feature. The scanned classes reach :app ONLY via :feature's
// generated definition hints — the cross-module discovery path that, before the fix, added the
// same definition once per discovery, emitting duplicate `single { }` registrations AND duplicate
// top-level hint functions. On JVM/DEX that is only a D8 warning; on a KLIB-serialized target
// (wasmJs / iosArm64) duplicate top-level declarations are a hard compile error.
pluginManagement {
    val kotlinVersion: String = (settings.extra.properties["kotlinVersion"] as? String) ?: "2.4.0"
    plugins {
        kotlin("multiplatform") version kotlinVersion
        id("io.insert-koin.compiler.plugin") version "1.0.2-Beta1"
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

rootProject.name = "app-kmp-klib-crossmodule"
include(":feature", ":app")
