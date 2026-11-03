// RUN_PIPELINE_TILL: BACKEND
// an empty-scan module behind a scan-less includes-only module, both off app's classpath.
// The relay must keep "complete and empty" across the wrapper, so validation runs.
//
// EXPECTED: KOIN-D001 for app.Missing (not KOIN-W008).

// MODULE: empty
// FILE: empty/Empty.kt
package empty

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("empty")
class EmptyModule

// MODULE: mid(empty)
// FILE: mid/Mid.kt
package mid

import empty.EmptyModule
import org.koin.core.annotation.Module

@Module(includes = [EmptyModule::class])
class MidModule

// MODULE: agg(mid)
// FILE: agg/Agg.kt
package agg

import mid.MidModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Single
class AggThing

@Module(includes = [MidModule::class])
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

class Missing

@Single
class Screen(val thing: AggThing, val missing: Missing)

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
