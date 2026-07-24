// Multiplatform KLIB verification app — proves synthetic hint functions are emitted ONCE so KLIB
// serialization (wasmJs/native) succeeds. JVM tolerates duplicate signatures; these targets are
// where they become a hard SignatureClashDetector error. Guards two dedup paths:
//   - injectedparams_* (compiler#40 wasmJs, #44 iOS) — the original repro.
//   - funcreqs_* (A3 Gate-3) — a top-level @Single fun with a dep, discovered by two @ComponentScan
//     modules over one package, must emit the requirements-carrier hint once (see App.kt).
//
// Kotlin 2.4.0: 2.3.20 hits the pre-existing KT-82395 ("No file found for source null") for ANY
// plugin-generated top-level declaration on wasm/native (reproduces on old plugin versions too),
// which masks the dedup checks. 2.4.0 fixes it and matches app-kmp-klib-crossmodule.
pluginManagement {
    val kotlinVersion: String = (settings.extra.properties["kotlinVersion"] as? String) ?: "2.4.0"
    plugins {
        kotlin("multiplatform") version kotlinVersion
        id("io.insert-koin.compiler.plugin") version "1.1.0-Beta1"
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
