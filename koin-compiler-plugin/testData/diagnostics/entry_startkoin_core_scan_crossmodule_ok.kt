// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — baseline matrix. Entry point = REAL koin-core `startKoin { modules(A, B) }`
// loading TWO annotation @ComponentScan modules across packages.
//
// Repository (scanned in `core`) is provided; Service (scanned in `feature`) depends on it.
// The graph is COMPLETE once both modules are loaded at the root.
//
// RESULT: empty .errors.txt (no diagnostic) — correct. The scoped A2→A3 shift defers
// FeatureModule's not-locally-visible cross-module scanned-class dep, and real koin-core
// `startKoin { modules(...) }` (org.koin.core.context.startKoin) now resolves its module closure
// (KoinStartTransformer walks the trailing lambda for modules(vararg KClass) calls and reifies the
// root), so the A3 full-graph pass assembles CoreModule + FeatureModule and resolves Repository —
// settling the deferral silently. (Before the A3 reshape this was a FALSE-POSITIVE KOIN-D001; an
// intermediate step left it at KOIN-W002 until real-koin-core-startKoin closure resolution landed.)
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
