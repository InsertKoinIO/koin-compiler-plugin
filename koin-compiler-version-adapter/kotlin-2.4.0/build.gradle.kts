// Adapter for the Kotlin 2.4 line — pinned to its own kotlin-compiler.
plugins {
    kotlin("jvm")
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        // Compiled by the build's Kotlin toolchain against a newer kotlin-compiler
        // artifact — skip metadata/prerelease checks for that boundary.
        freeCompilerArgs.addAll(
            "-Xskip-metadata-version-check",
            "-Xskip-prerelease-check",
        )
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler:2.4.0")
    compileOnly(project(":koin-compiler-version-adapter"))
}
