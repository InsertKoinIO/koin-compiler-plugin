import org.gradle.kotlin.dsl.koinCompiler

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

    sourceSets {
        commonMain.dependencies {
            // plugin-support is added automatically by the gradle plugin
            implementation(project(":sample-feature-module"))
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
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
