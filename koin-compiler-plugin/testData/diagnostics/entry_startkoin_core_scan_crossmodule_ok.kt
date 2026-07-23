// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — baseline matrix. Entry point = REAL koin-core `startKoin { modules(A, B) }`
// loading TWO annotation @ComponentScan modules across packages.
//
// Repository (scanned in `core`) is provided; Service (scanned in `feature`) depends on it.
// The graph is COMPLETE once both modules are loaded at the root.
//
// TARGET: empty .errors.txt (no diagnostic).
// PROBE: whether the per-module A2 pass false-positives on FeatureModule (Repository is a
// cross-module scanned class), the same false-positive class as cross_module_scanned_class_koinapp_ok
// but reached through the real koin-core startKoin root instead of the typed @KoinApplication root.
// FILE: core/Repository.kt
package core

import org.koin.core.annotation.Singleton

@Singleton
class Repository

// FILE: feature/Service.kt
package feature

import core.Repository
import org.koin.core.annotation.Singleton

@Singleton
class Service(val repo: Repository)

// FILE: app.kt
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

@Module
@ComponentScan("core")
class CoreModule

@Module
@ComponentScan("feature")
class FeatureModule

fun main() {
    startKoin {
        modules(CoreModule::class, FeatureModule::class)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
