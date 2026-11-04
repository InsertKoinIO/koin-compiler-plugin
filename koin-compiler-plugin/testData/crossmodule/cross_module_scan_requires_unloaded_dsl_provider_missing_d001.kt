// RUN_PIPELINE_TILL: BACKEND
// GH #124 guard: `lib` declares the DSL provider of CV11, but `app` loads a different DSL module
// from `lib`. Only DSL modules the entry point loads may count as providers.
//
// EXPECTED: KOIN-D001 for CV11.

// MODULE: lib
// FILE: lib/Module2.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan
class Module2

// FILE: lib/KModule.kt
package lib

import lib.v1.CV11
import lib.v1.Other
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

internal val innerModule = module {
    single<CV11>()
}

val module2Koin = module {
    includes(innerModule)
}

val otherKoin = module {
    single<Other>()
}

// FILE: lib/v1/CV1.kt
package lib.v1

import org.koin.core.annotation.Single

@Single
class CV1 internal constructor(private val cv11: CV11)

// FILE: lib/v1/CV11.kt
package lib.v1

internal class CV11

class Other

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.otherKoin
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.plugin.module.dsl.startKoin

@Module
@ComponentScan
@Configuration
class AppModule

@KoinApplication(modules = [AppModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {
        modules(otherKoin)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration */
