// RUN_PIPELINE_TILL: BACKEND
// GH #143 guard: a `factory` declared inside a named `scope(named(..)) { }` block is scoped too, like
// one inside a typed `scope<T> { }`. Loading the module must not turn it into a root provider.
//
// EXPECTED: KOIN-D001 for CV11.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.qualifier.named
import org.koin.dsl.module

@Module
@Configuration
@ComponentScan
class LibModule

class CV11

class Other

@Single
class CV1(val cv11: CV11)

val module2Koin = module {
    scope(named("lib")) {
        factory { CV11() }
    }
}

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.module2Koin
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
object MyApp

fun main() {
    startKoin<MyApp> {
        modules(module2Koin)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, objectDeclaration, primaryConstructor,
propertyDeclaration */
