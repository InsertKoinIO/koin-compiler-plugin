// RUN_PIPELINE_TILL: BACKEND
// A3 — falsify-first guard for the DSL includes-edge carrier.
//
// Companion to cross_module_dsl_transitive_includes_ok: SAME transitive topology, but the dependency
// is genuinely provided by nobody. The carrier widens the reachable module set, so the risk it
// introduces is the worst failure class — a real missing dependency going silent. This test pins that
// down: reaching baseModule through lib's `includes()` must make its definitions available WITHOUT
// making unprovided types resolve.
//
// Topology (identical to the _ok case except Repository is never provided):
//   base : class Repository            <-- declared, but NO single<Repository>()
//          val baseModule = module { single<Unrelated>() }
//   lib  : val libModule = module { includes(baseModule); single<Service>() }
//          Service(val repo: Repository) — needs a type nothing in the closure provides.
//   app  : val appModule = module { includes(libModule); single<AppThing>() }
//          startKoin { modules(appModule) }
//
// EXPECTED: KOIN-D001 for base.Repository. If this test ever goes silent, the carrier has stopped
// validating the modules it made reachable.

// MODULE: base
// FILE: base/Base.kt
package base

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Unrelated

val baseModule = module {
    single<Unrelated>()
}

// MODULE: lib(base)
// FILE: lib/Lib.kt
package lib

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import base.Repository
import base.baseModule

class Service(val repo: Repository)

val libModule = module {
    includes(baseModule)
    single<Service>()
}

// MODULE: app(lib, base)
// FILE: app/App.kt
package app

import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import lib.libModule

class AppThing

val appModule = module {
    includes(libModule)
    single<AppThing>()
}

fun main() {
    startKoin {
        modules(appModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
