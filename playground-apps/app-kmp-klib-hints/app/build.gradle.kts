plugins {
    kotlin("multiplatform")
    id("io.insert-koin.compiler.plugin")
}

kotlin {
    jvmToolchain(17)

    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }
    iosArm64 {
        binaries.framework { baseName = "ReproApp" }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.insert-koin:koin-core:4.2.2")
                implementation("io.insert-koin:koin-annotations:4.2.2")
                implementation(project(":lib-config"))
                implementation(project(":lib-plain"))
                implementation(project(":lib-dsl"))
            }
        }
    }
}
