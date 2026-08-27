// RUN_PIPELINE_TILL: BACKEND
// A3 — falsify-first guard for the transitive relay walk (companion to
// cross_module_dsl_aggregator_relay_ok).
//
// SAME bare-aggregator-in-the-middle topology as the _ok case, but the dependency is genuinely
// provided by nobody. Walking transitively through `relayModule` to reach `dataModule` widens what
// `app` can see, so the risk it introduces is the worst failure class — a real missing dependency
// going silent. This pins that down: reaching `dataModule` through `relayModule`'s `includes()` must
// make its (non-)definitions available WITHOUT making unprovided types resolve.
//
// Topology (identical to the _ok case except Repository is never provided):
//   core : class Repository                                    <-- declared, but NO single<Repository>()
//          val dataModule = module { single<Unrelated>() }
//          fun relayModule() = module { includes(dataModule) }  <-- 0 defs of its own, pure relay
//   app  : val appModule = module { includes(relayModule()); single<Consumer>() }
//          Consumer(val repo: Repository) — needs a type nothing in the closure provides.
//          startKoin { modules(appModule) }
//
// EXPECTED: KOIN-D001 for core.Repository. If this test ever goes silent, the transitive relay has
// stopped validating the modules it made discoverable.

// MODULE: core
// FILE: core/Core.kt
package core

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Unrelated

val dataModule = module {
    single<Unrelated>()
}

fun relayModule() = module {
    includes(dataModule)
}

// MODULE: app(core)
// FILE: app/App.kt
package app

import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import core.Repository
import core.relayModule

class Consumer(val repo: Repository)

val appModule = module {
    includes(relayModule())
    single<Consumer>()
}

fun main() {
    startKoin {
        modules(appModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
