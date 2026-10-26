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

// -Pfalsify=true adds a definition whose dependency is never provided: every target MUST report it.
if (providers.gradleProperty("falsify").orNull == "true") {
    kotlin.sourceSets.getByName("commonMain").kotlin.srcDir("src/falsify/kotlin")
}
