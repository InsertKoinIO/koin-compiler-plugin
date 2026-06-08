plugins {
    kotlin("jvm")
    id("io.insert-koin.compiler.plugin") version "1.0.1"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.insert-koin:koin-core:4.2.1")
}
