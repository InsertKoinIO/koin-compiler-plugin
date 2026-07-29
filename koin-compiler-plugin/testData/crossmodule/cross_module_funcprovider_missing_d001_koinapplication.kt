// RUN_PIPELINE_TILL: BACKEND
// Regression test for a mislabeled KOIN-D001 fallback found during 1.1.0 release review: the
// missing-dependency error's module-attribution fallback (used when a definition's real origin is
// unavailable — here, an ExternalFunctionDef reconstructed from a cross-module function-provider
// hint, see cross_module_funcprovider_missing_d001.kt for the general shape) hardcoded
// "$appName (startKoin)" regardless of which entry-point function actually assembled the graph.
// This is the SAME topology as cross_module_funcprovider_missing_d001.kt, but the entry point is
// `koinApplication<MyApp>()`, not `startKoin<MyApp>()`.
//
// EXPECTED: KOIN-D001 attributes to "MyApp (koinApplication)", not "MyApp (startKoin)".

// MODULE: base
// FILE: base/Base.kt
package base

import org.koin.core.annotation.Single

class Repository

@Single
fun provideRepository(): Repository = Repository()

// MODULE: lib(base)
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Single
import base.Repository

class Service(val repo: Repository)

@Single
fun provideService(repo: Repository): Service = Service(repo)

@Module
@ComponentScan("lib")
class LibModule

// MODULE: app(lib)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.koinApplication
import lib.LibModule

@KoinApplication(modules = [LibModule::class])
object MyApp

fun main() {
    koinApplication<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
