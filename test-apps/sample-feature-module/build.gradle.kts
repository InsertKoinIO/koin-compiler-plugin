plugins {
    kotlin("multiplatform")
    alias(libs.plugins.koin.plugin)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // plugin-support is added automatically by the gradle plugin
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
            implementation(libs.koin.viewmodel)
        }
    }
}

koinCompiler {
    userLogs = true
    debugLogs = true
}
