// RUN_PIPELINE_TILL: BACKEND
// #96: in one compilation, a @Named provider of Client processed before the unqualified one must
// not take the unqualified provider's carrier slot. The unqualified provider needs Store (provided
// nowhere): that KOIN-D001 must not be silently lost.
//
// EXPECTED: KOIN-D001 for Store only.

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
@Named("a")
fun provideNamedClient(): Client = Client()

@Single
fun provideDefaultClient(store: Store): Client = Client()

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
