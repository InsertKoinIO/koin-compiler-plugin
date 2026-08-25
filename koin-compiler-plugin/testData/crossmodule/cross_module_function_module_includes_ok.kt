// RUN_PIPELINE_TILL: BACKEND
// Function-returned-module analogue of cross_module_dsl_transitive_includes_ok.kt: the same
// cross-module transitive `includes()` reachability, but every module is `fun ... () = module {}`
// instead of `val ... = module {}` — the dominant real-world shape (production monorepo survey).
//
// Topology — the edge that matters (lib -> base) is declared inside lib's lambda:
//   base : fun baseModule() = module { single<Repository>() }
//   lib  : fun libModule()  = module { includes(baseModule()); single<Service>() }   <-- cross-module edge
//          Service(val repo: Repository) — its dep lives in base, reachable ONLY via lib's includes.
//   app  : fun appModule()  = module { includes(libModule()); single<AppThing>() }   <-- local edge (visible)
//          startKoin { modules(appModule()) }
//
// EXPECTED: SILENT. appModule() -> libModule() -> baseModule() is a complete graph.

// MODULE: base
// FILE: base/Base.kt
package base

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository

fun baseModule() = module {
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

fun libModule() = module {
    includes(baseModule())
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

fun appModule() = module {
    includes(libModule())
    single<AppThing>()
}

fun main() {
    startKoin {
        modules(appModule())
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
