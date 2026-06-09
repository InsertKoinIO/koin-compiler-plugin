plugins {
    kotlin("multiplatform")
    id("io.insert-koin.compiler.plugin")
}

kotlin {
    jvmToolchain(17)

    // wasmJs: a KLIB target (no Xcode needed) — serialization rejects duplicate
    // injectedparams_* signatures, the actual failure mode of compiler#40/#44.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }

    // iOS device target — the failing target in compiler#44. KLIB-serialized like
    // wasmJs, so it exercises the same duplicate-signature path on the native backend.
    iosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.insert-koin:koin-core:4.2.1")
                implementation("io.insert-koin:koin-annotations:4.2.1")
            }
        }
    }
}
