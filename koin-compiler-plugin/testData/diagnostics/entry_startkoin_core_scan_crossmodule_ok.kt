// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — baseline matrix. Entry point = REAL koin-core `startKoin { modules(A, B) }`
// loading TWO annotation @ComponentScan modules across packages.
//
// Repository (scanned in `core`) is provided; Service (scanned in `feature`) depends on it.
// The graph is COMPLETE once both modules are loaded at the root.
//
// RESULT: KOIN-W002 (deferred), NOT the former FALSE-POSITIVE KOIN-D001. The scoped A2→A3
// shift now defers FeatureModule's not-locally-visible cross-module scanned-class dep instead
// of hard-erroring it. The remaining gap to full silence: this root is REAL koin-core
// `startKoin { modules(...) }` (org.koin.core.context.startKoin), which is still flag-only —
// its module closure isn't resolved, so A3 can't settle the deferral here and it flushes to
// W002. The typed @KoinApplication / stub-startKoin form (cross_module_scanned_class_koinapp_ok)
// resolves fully to empty. Wiring real-koin-core-startKoin closure resolution (Gate-1 follow-up)
// will take this to empty too. Net: the false hard error is gone; a deferred warning remains.
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
