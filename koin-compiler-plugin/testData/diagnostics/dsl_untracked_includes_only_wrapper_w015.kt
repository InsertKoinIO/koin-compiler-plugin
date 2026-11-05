// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Wrapper modules whose body is nothing but includes(). One warning per block, with the advice that
// fits: pass the included module directly. `listOf(module { includes(..) })` is identified through
// listOf, so its dropped edge is a KOIN-W012; the `by lazy` wrapper is untracked, so it gets one
// KOIN-W015 and no KOIN-W012 on its includes() (W015 says the most).
//
// EXPECTED: one KOIN-W012 at the listOf-wrapped includes(), one KOIN-W015 at the lazy `module {`,
// both with the "pass them to modules() directly" advice. The false KOIN-W001 for both included
// modules stay (validation unchanged in 1.2.3).
package testpkg

import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

class Repository
class Cache

val dataModule = module {
    single { Repository() }
}

val cacheModule = module {
    single { Cache() }
}

val lazyWrapper: Module by lazy {
    module { includes(cacheModule) }
}

fun boot() {
    startKoin {
        modules(listOf(module { includes(dataModule) }))
        modules(lazyWrapper)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, nullableType, propertyDeclaration,
propertyDelegate */
