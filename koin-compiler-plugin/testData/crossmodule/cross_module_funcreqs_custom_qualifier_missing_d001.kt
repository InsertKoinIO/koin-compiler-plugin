// RUN_PIPELINE_TILL: BACKEND
// #96 guard: a provider qualified by a CUSTOM qualifier annotation, in a scanned package, still
// carries its requirements across modules after the bare fallback carrier was removed. Store is
// provided nowhere.
//
// EXPECTED: KOIN-D001 for Store, required by the @Remote Client provider.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Qualifier
import org.koin.core.annotation.Single

@Qualifier
annotation class Remote

class Store
class Client

@Module
@ComponentScan("lib")
class LibModule

@Single
@Remote
fun provideRemoteClient(store: Store): Client = Client()

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.LibModule
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules

fun main() {
    startKoin { modules(LibModule::class) }
}

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, classReference, functionDeclaration, lambdaLiteral,
stringLiteral */
