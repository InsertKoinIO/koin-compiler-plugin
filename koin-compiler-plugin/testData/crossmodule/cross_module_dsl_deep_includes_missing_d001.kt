// RUN_PIPELINE_TILL: BACKEND
// A3 — falsify-first guard for the DSL definition-hint relay (companion to
// cross_module_dsl_deep_includes_ok).
//
// SAME transitive, classpath-invisible topology as the _ok case, but the dependency is genuinely
// provided by nobody. Relaying `data`'s dsl_single hints through `feature` widens what `app` can
// see, so the risk it introduces is the worst failure class — a real missing dependency going
// silent. This pins that down: reaching `data` through `feature`'s `includes()` must make its
// (non-)definitions available WITHOUT making unprovided types resolve.
//
// Topology (identical to the _ok case except Repository is never provided):
//   data    : class Repository                <-- declared, but NO single<Repository>()
//             val dataModule = module { single<Unrelated>() }
//   feature : val featureModule = module { includes(dataModule); single<Service>() }
//             Service(val repo: Repository) — needs a type nothing in the closure provides.
//   app     : val appModule = module { includes(featureModule); single<AppThing>() }
//             startKoin { modules(appModule) }
//
// EXPECTED: KOIN-D001 for data.Repository. If this test ever goes silent, the relay has stopped
// validating the modules it made discoverable.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Unrelated

val dataModule = module {
    single<Unrelated>()
}

// MODULE: feature(data)
// FILE: feature/Feature.kt
package feature

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import data.Repository
import data.dataModule

class Service(val repo: Repository)

val featureModule = module {
    includes(dataModule)
    single<Service>()
}

// MODULE: app(feature)
// FILE: app/App.kt
package app

import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import feature.featureModule

class AppThing

val appModule = module {
    includes(featureModule)
    single<AppThing>()
}

fun main() {
    startKoin {
        modules(appModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
