plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    id("io.insert-koin.compiler.plugin") apply false
}

allprojects {
    group = "io.insert-koin.sample"
}
