// RUN_PIPELINE_TILL: BACKEND
// Falsify-first companion to cross_module_dsl_singleof_narrowed_dependency_no_false_cycle
// (testData/box/modules): that test proves singleOf's cross-module hint reconstruction no longer
// false-cycles now that singleOf's requirements are derived at all (see
// KoinDSLTransformer.collectConstructorShorthandDef) and encoded into the hint via the SAME
// req0_/reqsEncoded mechanism create(::function) already uses (966d09a) — on its own that test
// only proves SILENCE. This is the other half: a GENUINELY missing dependency for the referenced
// function must still be caught cross-module.
//
// Topology:
//   lib : val libModule = module { singleOf(::buildService) }
//         fun buildService(repo: Repository): Service. Repository is provided by NOBODY.
//   app : has its own entry point (startKoin) + a local DSL def, which triggers the DSL-graph pass
//         that discovers lib's Service via its dsl_ hint and validates it → Repository missing.
//
// EXPECTED: KOIN-D001 Missing dependency: Repository, required by the cross-module DSL provider.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

class Repository
class Service(val repo: Repository)

fun buildService(repo: Repository): Service = Service(repo)

val libModule = module {
    singleOf(::buildService)
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
