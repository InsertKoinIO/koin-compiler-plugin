plugins {
    kotlin("multiplatform")
    id("io.insert-koin.compiler.plugin") version "1.2.1"
}

kotlin {
    jvmToolchain(17)

    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }
    iosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.insert-koin:koin-core:4.2.2")
                implementation("io.insert-koin:koin-annotations:4.2.2")
            }
        }
    }
}
