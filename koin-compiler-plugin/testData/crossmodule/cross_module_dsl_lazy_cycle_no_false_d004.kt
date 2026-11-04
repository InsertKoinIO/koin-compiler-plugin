// RUN_PIPELINE_TILL: BACKEND
// GH #115 — cross-module DSL hint drops the Lazy flag.
//
// A -> B -> Lazy<A>: Lazy<T> is the canonical runtime cycle breaker, so the same shape inside one
// module is accepted (diagnostics/circular_dependency_broken_by_lazy.kt). Cycle detection skips an
// edge only when Requirement.isLazy is set (BindingRegistry). Seen from app, lib's definitions are
// rebuilt from the dsl_ hint with isLazy hard-coded false → the Lazy edge becomes a hard edge →
// FALSE KOIN-D004 at app's entry point.
//
// EXPECTED: SILENT.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class A(val b: B)
class B(val a: Lazy<A>)

val libModule = module {
    single<A>()
    single<B>()
}

// MODULE: app(lib)
// FILE: app/App.kt
package app

import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import lib.libModule

class AppThing

val appModule = module {
    includes(libModule)
    single<AppThing>()
}

fun main() {
    startKoin {
        modules(appModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
