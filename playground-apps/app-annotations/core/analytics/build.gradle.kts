plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.koin.compiler)
}

android {
    namespace = "org.koin.sample.core.analytics"
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
    implementation(libs.jakarta.inject)
    implementation(libs.koin.android)
    implementation(libs.koin.annotations)
}
