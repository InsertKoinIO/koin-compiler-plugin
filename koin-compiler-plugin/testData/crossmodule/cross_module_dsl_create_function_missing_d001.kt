// RUN_PIPELINE_TILL: BACKEND
// Falsify-first companion to cross_module_dsl_create_function_narrowed_dependency_no_false_cycle
// (testData/box/modules): that test proves a create(::function) DSL definition no longer
// false-cycles cross-module now that its requirements are encoded via the hint's req0_/reqsEncoded
// params (the referenced function's own parameters) instead of guessed from the return type's
// constructor. On its own it only proves SILENCE — a fix that also broke real requirement encoding
// would pass it too. This is the other half: a GENUINELY missing dependency for the referenced
// function must still be caught.
//
// Topology:
//   lib : val libModule = module { single { create(::buildService) } }
//         fun buildService(repo: Repository): Service. Repository is provided by NOBODY.
//   app : has its own entry point (startKoin) + a local DSL def, which triggers the DSL-graph pass
//         that discovers lib's Service via its dsl_ hint and validates it → Repository missing.
//
// EXPECTED: KOIN-D001 Missing dependency: Repository, required by the cross-module DSL provider.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.dsl.module
import org.koin.plugin.module.dsl.create

class Repository
class Service(val repo: Repository)

fun buildService(repo: Repository): Service = Service(repo)

val libModule = module {
    single { create(::buildService) }
}

// MODULE: app(lib)
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
