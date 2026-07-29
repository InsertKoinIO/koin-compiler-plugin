// RUN_PIPELINE_TILL: BACKEND
// A3 — cross-module DSL transitive `includes()` reachability (the includes-edge carrier).
//
// A DSL module's membership lives in its `module { }` LAMBDA BODY, which is NOT part of any
// declaration's ABI. So a consumer can walk its OWN `includes()` calls, but an `includes()` edge
// declared inside a DEPENDENCY module is invisible — the edge simply doesn't survive compilation.
//
// Before the includes-edge hint, CallSiteValidator.computeReachableModules therefore only knew the
// edges it could see locally. Any module reached ONLY through a dependency's `includes()` was
// classified UNREACHABLE, its definitions were dropped from the provider set, and every consumer of
// them hard-errored — a FALSE KOIN-D001 on a graph that resolves perfectly at runtime (Koin follows
// `includes` transitively). It also mis-fired KOIN-W001 "not loaded at startKoin".
//
// The practical effect was that users had to redundantly re-list every transitive module at the
// root, defeating the purpose of `includes()`. Reproduced on the playground app-dsl by deleting the
// redundant re-listing from appModule: 9 false KOIN-D001 on an unchanged, valid graph.
//
// Topology — the edge that matters (lib -> base) is declared inside lib's lambda:
//   base : val baseModule = module { single<Repository>() }
//   lib  : val libModule  = module { includes(baseModule); single<Service>() }   <-- cross-module edge
//          Service(val repo: Repository) — its dep lives in base, reachable ONLY via lib's includes.
//   app  : val appModule  = module { includes(libModule); single<AppThing>() }   <-- local edge (visible)
//          startKoin { modules(appModule) }
//
// EXPECTED (after the carrier): SILENT. appModule -> libModule -> baseModule is a complete graph.
// A stale/absent includes hint degrades to the old behavior (fewer reachable modules), never to a
// wrong-but-silent pass — the carrier only ever ADDS reachability, and only from real emitted IR.

// MODULE: base
// FILE: base/Base.kt
package base

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository

val baseModule = module {
    single<Repository>()
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
