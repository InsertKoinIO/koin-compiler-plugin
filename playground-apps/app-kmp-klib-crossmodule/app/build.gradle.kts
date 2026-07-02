plugins {
    kotlin("multiplatform")
    id("io.insert-koin.compiler.plugin")
}

kotlin {
    jvmToolchain(17)

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }
    iosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":feature"))
                implementation("io.insert-koin:koin-core:4.2.1")
                implementation("io.insert-koin:koin-annotations:4.2.1")
            }
        }
    }
}
