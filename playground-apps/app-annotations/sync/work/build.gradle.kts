plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.koin.compiler)
}

android {
    namespace = "org.koin.sample.sync"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

koinCompiler {
    userLogs = true
    debugLogs = true
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.data)

    implementation(libs.workmanager)
    implementation(libs.kotlinx.coroutines)

    implementation(libs.koin.android)
    implementation(libs.koin.annotations)
    implementation(libs.koin.workmanager)
}
