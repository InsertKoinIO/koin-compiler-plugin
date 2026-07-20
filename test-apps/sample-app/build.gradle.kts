@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.gradle.kotlin.dsl.koinCompiler
import org.gradle.kotlin.dsl.kotlin
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.koin.plugin)
}

kotlin {
    jvm {
        mainRun {
            mainClass.set("examples.annotations.AnnotationsConfigTestKt")
        }
    }

    // KLIB-based targets to verify FIR generation is properly skipped (KT-82395)
    js(IR) {
        browser()
        nodejs()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            // plugin-support is added automatically by the gradle plugin
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
        }

        jvmMain.dependencies {
            // Cross-module dependency only for JVM (sample-feature-module is JVM-only)
            implementation(project(":sample-feature-module"))
            implementation(libs.koin.viewmodel)
        }

        jvmTest.dependencies {
            implementation(libs.koin.test.junit4)
        }
    }
}

koinCompiler {
    userLogs = true
    debugLogs = true
}
