// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// A `module { }` compile safety cannot identify: here a `by lazy` property. Its
// definitions get no module id, so they count as loaded whatever the entry point loads. The inline
// `module { }` passed straight to modules() is identified by that call and must stay silent.
//
// EXPECTED: one KOIN-W015 at the `module {` inside lazy, naming the rewrite. Nothing for the
// inline module passed to modules(). No D001/W001.
package testpkg

import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)

val lazyModule by lazy {
    module {
        single<Repository>()
    }
}

fun main() {
    startKoin {
        modules(
            lazyModule,
            module { single<Service>() },
        )
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, nullableType, primaryConstructor,
propertyDeclaration, propertyDelegate */
