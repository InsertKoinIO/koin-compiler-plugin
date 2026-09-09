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
    // Runnable native target for the KTZ-4829 direct-registration key check (qualifiedName on Native).
    macosArm64 {
        binaries.executable { entryPoint = "playground.main" }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.insert-koin:koin-core:4.2.1")
                implementation("io.insert-koin:koin-annotations:4.2.1")
            }
        }
    }
}

// KTZ-4829: every directly registered definition re-checks its compile-time index key against
// indexKey() at module load; a derivation mismatch on wasmJs (simpleName) or Native (qualifiedName)
// throws in main() instead of surfacing as a NoDefinitionFoundException.
koinCompiler {
    verifyGeneratedKeys = true
}
