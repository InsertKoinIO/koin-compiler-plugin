// RUN_PIPELINE_TILL: BACKEND
// GH #115 — annotation-side twin of cross_module_dsl_lazy_cycle_no_false_d004.
//
// Function providers in a dependency module carry their requirements to the consumer through the
// funcreqs hint (KoinAnnotationProcessor). Its producer already filters with requiresValidation(),
// so no false D001 — but Lazy<T> is kept (correctly, T must exist) and the decoder rebuilds it with
// isLazy hard-coded false → the Lazy edge becomes a hard edge → FALSE KOIN-D004 at app's entry point.
//
// EXPECTED: SILENT.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

class A(val b: B)
class B(val a: Lazy<A>)

@Single
fun provideA(b: B): A = A(b)

@Single
fun provideB(a: Lazy<A>): B = B(a)

@Module
@ComponentScan("lib")
class LibModule

// MODULE: app(lib)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin
import lib.LibModule

@KoinApplication(modules = [LibModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
