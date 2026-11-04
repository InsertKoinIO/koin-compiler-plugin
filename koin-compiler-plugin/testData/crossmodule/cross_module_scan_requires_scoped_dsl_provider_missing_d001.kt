// RUN_PIPELINE_TILL: BACKEND
// GH #124 guard: CV11 is only `scoped` in a scope the root-scope CV1 can't see. Loading the module
// must not turn it into a root provider.
//
// EXPECTED: KOIN-D001 for CV11.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped

@Module
@Configuration
@ComponentScan
class LibModule

class CV11

class Other

@Single
class CV1(val cv11: CV11)

class LibScope

val module2Koin = module {
    scope<LibScope> {
        scoped<CV11>()
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
