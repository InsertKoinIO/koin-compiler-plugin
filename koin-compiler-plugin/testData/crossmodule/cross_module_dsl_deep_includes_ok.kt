// RUN_PIPELINE_TILL: BACKEND
// A3 — falsify-first guard for DSL definition hints 2+ implementation hops away.
//
// Companion/contrast to cross_module_dsl_transitive_includes_ok: that test proves the includes-edge
// TOPOLOGY carrier works, but it declares `app(lib, base)` — app has a DIRECT dependency on `base`,
// so `context.referenceFunctions` can resolve base's dsl_single hint straight off app's own
// classpath regardless of the includes() carrier. It never exercises the case where the PROVIDER
// module itself is classpath-invisible, only reachable via a dependency's own `includes()` — same
// gap class as the annotation-side @Module(includes=[...]) / @Configuration fixes (686b8c1, 55b1271),
// but for the base DSL `dsl_single`/`dsl_factory`/etc. hints themselves.
//
// Topology — app depends ONLY on feature (implementation), feature depends ONLY on data:
//   data    : val dataModule = module { single<Repository>() }
//   feature : val featureModule = module { includes(dataModule); single<Service>() }
//             Service(val repo: Repository) — dep lives in `data`, 2 implementation hops from app.
//   app     : val appModule = module { includes(featureModule); single<AppThing>() }
//             startKoin { modules(appModule) }
//
// `app` never depends on `data` directly — only `feature` does. If discoverDslDefinitionsFromHints()
// can only see dsl_single hints on THIS compilation's own classpath, `data`'s Repository provider is
// invisible to `app`'s validation pass even though the includes-edge walk correctly marks `data`
// reachable — a false KOIN-D001 on a graph that resolves perfectly at runtime.
//
// EXPECTED (after the fix): SILENT. appModule -> featureModule -> dataModule is a complete graph.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository

val dataModule = module {
    single<Repository>()
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
