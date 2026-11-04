// RUN_PIPELINE_TILL: BACKEND
// GH #115 — guard for the flag fix: Lazy<T> is NOT a "no provider needed" flag.
//
// Lazy only defers resolution; T must still be provided. Decoding isLazy on the consumer must keep
// this a hard requirement for missing-dependency validation (only cycle detection skips Lazy edges).
// Fails if the fix over-reaches and treats isLazy like isNullable/isList.
//
// EXPECTED: KOIN-D001 Missing dependency: lib.Repository, required by dsl:lib.Service.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Lazy<Repository>)

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
