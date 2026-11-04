// RUN_PIPELINE_TILL: BACKEND
// GH #124 guard: the typed entry point's own graph has no definition. Loading a DSL module from
// `lib` must not change how call sites are checked.
//
// EXPECTED: same output as before the GH #124 fix.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

@Module
@ComponentScan
class UnloadedLibModule

@Single
class Repo

class Foo

val featureKoin = module {
    single<Foo>()
}

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.Repo
import lib.featureKoin
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.plugin.module.dsl.startKoin

@Module
class AppModule

@KoinApplication(modules = [AppModule::class])
object MyApp

class Screen : KoinComponent {
    val repo: Repo by inject()
}

fun main() {
    startKoin<MyApp> {
        modules(featureKoin)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, propertyDeclaration, propertyDelegate */
