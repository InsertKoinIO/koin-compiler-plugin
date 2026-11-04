// RUN_PIPELINE_TILL: BACKEND
// two @Named("x") providers of DIFFERENT types in one scanned module must both reach the
// consumer. The second's entry hint was dropped: false KOIN-D001 for @Named("x") B.
//
// EXPECTED: no diagnostics.
// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

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
fun provideB(): B = B()

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.B
import lib.LibModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules

@Single
class User(@Named("x") val b: B)

@Module
@ComponentScan("app")
class AppModule

fun main() {
    startKoin { modules(LibModule::class, AppModule::class) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, lambdaLiteral, primaryConstructor,
propertyDeclaration, stringLiteral */
