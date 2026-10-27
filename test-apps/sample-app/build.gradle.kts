import org.gradle.kotlin.dsl.koinCompiler

plugins {
    kotlin("multiplatform")
    id("io.insert-koin.compiler.plugin")
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

    // Native KLIB: duplicate-signature and hint-visibility errors don't show on JVM
    iosArm64()

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
