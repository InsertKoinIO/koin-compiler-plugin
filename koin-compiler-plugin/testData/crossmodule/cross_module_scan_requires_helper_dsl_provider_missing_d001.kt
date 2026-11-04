// RUN_PIPELINE_TILL: BACKEND
// GH #124 guard: a DSL definition in a `Module.x()` helper has no module id, so nothing proves it is
// loaded. It must not count as a provider for the typed entry point.
//
// EXPECTED: KOIN-D001 for CV11, the helper is never called.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import org.koin.core.module.Module as KoinModule

@Module
@Configuration
@ComponentScan
class LibModule

class CV11

class Other

@Single
class CV1(val cv11: CV11)

fun KoinModule.libDefinitions() {
    single<CV11>()
}

val otherKoin = module {
    single<Other>()
}

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.otherKoin
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
object MyApp

fun main() {
    startKoin<MyApp> {
        modules(otherKoin)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral, objectDeclaration,
primaryConstructor, propertyDeclaration */
