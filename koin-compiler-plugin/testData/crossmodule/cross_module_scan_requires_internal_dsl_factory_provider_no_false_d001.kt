// RUN_PIPELINE_TILL: BACKEND
// GH #143 (follow-up of GH #124): an @Single class found by @ComponentScan in `lib` requires an
// `internal` class that only a DSL `factory<T>()` in `lib` provides. `app` loads both: the
// @Configuration module through @KoinApplication, the DSL module through `modules()` in the same
// startKoin block. A plain `factory` in a `module { }` body is a root-scope provider like `single`;
// only a `factory` inside a `scope { }` block is scoped (see *_scope_factory_*).
//
// EXPECTED: no diagnostics — CV11 is provided at runtime.

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
import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory

internal val innerModule = module {
    factory<CV11>()
}

val module2Koin = module {
    includes(innerModule)
}

// FILE: lib/v1/CV1.kt
package lib.v1

import org.koin.core.annotation.Single

@Single
class CV1 internal constructor(private val cv11: CV11)

// FILE: lib/v1/CV11.kt
package lib.v1

internal class CV11

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.module2Koin
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.startKoin

@Module
@ComponentScan
@Configuration
class AppModule

internal fun rootKoinModules() = module {
    includes(module2Koin)
}

@KoinApplication(modules = [AppModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {
        modules(rootKoinModules())
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration */
