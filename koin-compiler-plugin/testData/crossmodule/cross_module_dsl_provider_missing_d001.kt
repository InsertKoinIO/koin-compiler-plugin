// RUN_PIPELINE_TILL: BACKEND
// A3 — RED→GREEN for the cross-module DSL-provider requirement gap (companion to the function
// carrier). A DSL definition `single<Service>()` in a dependency module is discovered at the
// consumer via its dsl_* hint and reconstructed as a Definition.DslDef. Before the fix that
// reconstruction carried ZERO requirements (unlike the LOCAL DslDef, which derives them from the
// class constructor via attachA3Metadata), so the entry point never validated the DSL provider's
// constructor dependencies → a missing dep was a silent false negative (reproduced on app-dsl:
// removing the Notifier provider that single<OfflineFirstNewsRepository>() needs compiled green).
//
// Fix: DslHintGenerator re-derives requirements from the provided class's constructor (the class is
// ABI-available on the consumer classpath), mirroring the local DslDef. Now the app root emits
// KOIN-D001 for Service's missing Repository.
//
// Topology:
//   lib : val libModule = module { single<Service>() }; Service(val repo: Repository). Repository is
//         provided by NOBODY. lib has no entry point, so its DSL defs aren't validated here (silent).
//   app : has its own entry point (startKoin) + a local DSL def, which triggers the DSL-graph pass;
//         that pass discovers lib's Service via its dsl_ hint and validates it → Repository missing.
//
// EXPECTED: KOIN-D001 Missing dependency: Repository, required by the cross-module DSL provider.

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
