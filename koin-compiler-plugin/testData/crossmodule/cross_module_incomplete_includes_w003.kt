// RUN_PIPELINE_TILL: BACKEND
// A dependency's PARTIAL topology must not be read as fact by the consumer.
//
// The incomplete-includes signal is per-compilation state. When `:core` compiles, it knows its own
// `includes(...)` could not be fully read — but it has no entry point, so it validates nothing and
// simply emits its includes hint carrying whatever edges it did resolve. Nothing in the hint says
// "this list is partial".
//
// The consumer then walks that hint as if it were complete. Every module reachable only through the
// unreadable argument looks unloaded: KOIN-W001 for it, its types withheld from call sites, and a
// KOIN-D002 on a graph that is correct at runtime. That is the same false positive 9ac9609 fixed
// locally, surviving one module away.
//
// Topology:
//   feature : val featureModule = module { single<Repository>() }
//   core    : val featureList = if (isDynamic()) ... else ...   <- not statically readable
//             val coreModule  = module { includes(featureList); single<Service>() }
//   (`listOf(...)` alone is now resolved precisely — see dsl_module_listof_* — so a runtime
//   conditional is used here to keep this genuinely unresolvable.)
//   app     : val appModule = module { single<AppThing>() }   <- a local def, so the DSL graph pass runs
//             startKoin { modules(coreModule, appModule) }; koin.get<Repository>()
//
// EXPECTED: KOIN-W003 disclosing that the graph is not verifiable, and NO KOIN-D001 / KOIN-D002 /
// KOIN-W001 — the app's graph resolves fine at runtime. Reading a partial edge list as authoritative
// is what must not happen.

// MODULE: feature
// FILE: feature/Feature.kt
package feature

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository

val featureModule = module {
    single<Repository>()
}

// MODULE: core(feature)
// FILE: core/Core.kt
package core

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import feature.Repository
import feature.featureModule

class Service(val repo: Repository)

fun isDynamic(): Boolean = true
val featureList = if (isDynamic()) listOf(featureModule) else listOf(featureModule)

val coreModule = module {
    includes(featureList)
    single<Service>()
}

// MODULE: app(core, feature)
// FILE: app/App.kt
package app

import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import core.coreModule
import feature.Repository

class AppThing

val appModule = module {
    single<AppThing>()
}

fun main() {
    val koin = startKoin {
        modules(coreModule, appModule)
    }.koin
    val repo = koin.get<Repository>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty, primaryConstructor,
propertyDeclaration */
