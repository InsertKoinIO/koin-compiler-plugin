// RUN_PIPELINE_TILL: BACKEND
// an empty-scan @Module two `implementation` hops from the entry point (`app` doesn't see
// `empty`). The middle module must re-publish "scanned, found nothing", or `app` withholds validation
// for the whole entry point (KOIN-W008).
//
// EXPECTED: no diagnostics.

// MODULE: empty
// FILE: empty/Empty.kt
package empty

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("empty")
class EmptyModule

// MODULE: agg(empty)
// FILE: agg/Agg.kt
package agg

import empty.EmptyModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Single
class AggThing

@Module(includes = [EmptyModule::class])
@ComponentScan("agg")
class AggModule

// MODULE: app(agg)
// FILE: app/App.kt
package app

import agg.AggModule
import agg.AggThing
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

@Single
class Screen(val thing: AggThing)

@Module(includes = [AggModule::class])
@ComponentScan("app")
class AppModule

@KoinApplication(modules = [AppModule::class])
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
