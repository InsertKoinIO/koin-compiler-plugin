// RUN_PIPELINE_TILL: BACKEND
// An entry point in a module with no DSL definition of its own validates no DSL graph
// (GH #142): the dependency's missing Repository is not reported. 1.2.3 does not close the
// gap, it discloses it on the entry point.
//
// Topology:
//   lib : val libModule = module { single<Service>() }, Service needs a Repository nobody provides
//   app : startKoin { modules(libModule) }, no local DSL definition
//
// EXPECTED: one KOIN-W013 anchored at the startKoin call. No KOIN-D001 (the known gap).

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)

val libModule = module {
    single<Service>()
}

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.libModule
import org.koin.core.context.startKoin

fun main() {
    startKoin {
        modules(libModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
