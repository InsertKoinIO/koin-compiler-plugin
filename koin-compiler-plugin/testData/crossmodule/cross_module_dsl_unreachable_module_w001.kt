// RUN_PIPELINE_TILL: BACKEND
// A3 — second falsify-first guard for the DSL includes-edge carrier: KOIN-W001 must survive it.
//
// The carrier makes modules reachable through a dependency's `includes()`. A blunt implementation
// (e.g. "if a module has a hint, treat it as loaded") would make EVERY module on the classpath look
// reachable and silently retire KOIN-W001, which is what tells a user a module they wrote is never
// loaded. Reachability must still be a walk from the entry point, not classpath membership.
//
// Topology — baseModule is on the classpath and emits hints, but NOTHING includes it:
//   base : val baseModule = module { single<Repository>() }   <-- never included, never in modules()
//   lib  : val libModule  = module { single<Service>() }      <-- no includes() at all
//   app  : val appModule  = module { includes(libModule); single<AppThing>() }
//          startKoin { modules(appModule) }
//
// EXPECTED: KOIN-W001 naming baseModule as not loaded. Service takes no dependencies, so there is
// nothing else to report — the warning must appear on its own.

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

class Service

val libModule = module {
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

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, propertyDeclaration */
