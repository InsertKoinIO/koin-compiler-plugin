// RUN_PIPELINE_TILL: BACKEND
// the second @Named("x") provider (of another type) needs Store, provided nowhere. Its
// entry hint was dropped, so that KOIN-D001 was silently lost.
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
class A
class B

@Module
@ComponentScan("lib")
class LibModule

@Single
@Named("x")
fun provideA(): A = A()

@Single
@Named("x")
fun provideB(store: Store): B = B()

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
