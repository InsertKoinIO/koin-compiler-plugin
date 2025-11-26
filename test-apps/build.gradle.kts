plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.koin.plugin) apply false
}

allprojects {
    group = "io.insert-koin.sample"
}
