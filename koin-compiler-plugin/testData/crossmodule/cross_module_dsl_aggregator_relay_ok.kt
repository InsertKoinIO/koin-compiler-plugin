// RUN_PIPELINE_TILL: BACKEND
// A3 — falsify-first guard for the DSL definition-hint relay's transitive walk (companion/contrast to
// cross_module_dsl_deep_includes_ok, which covers a plain val-chain 2-hop case).
//
// Real-world shape that motivated this (Kotzilla server): a THREE-Gradle-module chain where the
// MIDDLE module is a bare aggregator with ZERO definitions of its own —
//   batchKoinModule() [app]  ->  repositoryModule() [shared/repositories, 0 defs, includes 2 more]
//   -> sharedRepositoriesModule() [shared/repositories, the REAL provider]
// The one-hop relay (relayIncludedDefinitionHints, first version) correctly identifies
// `repositoryModule` as a non-local include and relays ITS OWN defs — but it owns none, so that's a
// no-op. The real provider one level further inside was never relayed, since nothing walked past the
// immediate edge. This test reproduces that exact shape with a bare aggregator function sitting
// between the entry module and the real provider, in the SAME dependency Gradle module.
//
// Topology — app depends ONLY on core; the aggregator and the real provider are BOTH in core:
//   core : val dataModule = module { single<Repository>() }
//          fun relayModule() = module { includes(dataModule) }     <-- 0 defs of its own, pure relay
//   app  : val appModule = module { includes(relayModule()); single<Consumer>() }
//          Consumer(val repo: Repository) — dep lives 2 DSL-include-hops into `core`.
//          startKoin { modules(appModule) }
//
// EXPECTED (after the transitive-walk fix): SILENT. appModule -> relayModule -> dataModule is a
// complete graph.

// MODULE: core
// FILE: core/Core.kt
package core

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository

val dataModule = module {
    single<Repository>()
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
