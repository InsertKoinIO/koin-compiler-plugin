// RUN_PIPELINE_TILL: BACKEND
// #96: a qualifier with a character above U+00FF must name the same requirements carrier on both
// sides. It didn't round-trip through the hint encoding, so the consumer's lookup missed and the
// provider's missing Store went unreported.
//
// EXPECTED: KOIN-D001 for Store.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

class Store
class Client

@Module
@ComponentScan("lib")
class LibModule

@Single
@Named("a、b")
fun provideClient(store: Store): Client = Client()

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.LibModule
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules

fun main() {
    startKoin { modules(LibModule::class) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, lambdaLiteral, stringLiteral */
