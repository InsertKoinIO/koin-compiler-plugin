// Adapter for the Kotlin 2.3.20 line — pinned to its own kotlin-compiler.
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
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler:2.3.20")
    compileOnly(project(":koin-compiler-version-adapter"))
}
