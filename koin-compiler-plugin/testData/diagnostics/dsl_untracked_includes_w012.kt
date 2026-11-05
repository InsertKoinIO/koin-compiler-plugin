// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// includes() inside a module compile safety cannot identify is dropped: the
// included module looks unloaded. Two shapes: an inline `module { }` passed to modules(), and a
// public `fun Module.wire()` helper. Both are correct Koin code.
//
// EXPECTED: one KOIN-W012 at each includes() line, naming the shape that is followed. Validation
// itself is unchanged in 1.2.3, so the false KOIN-W001 for the included modules and the false
// KOIN-D001 for the types they provide stay: this correct code still fails to build.
package testpkg

import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Cache
class Service(val repo: Repository, val cache: Cache)

val dataModule = module { single<Repository>() }
val cacheModule = module { single<Cache>() }

fun Module.wire() {
    includes(cacheModule)
}

fun main() {
    startKoin {
        modules(module {
            includes(dataModule)
            wire()
            single<Service>()
        })
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
