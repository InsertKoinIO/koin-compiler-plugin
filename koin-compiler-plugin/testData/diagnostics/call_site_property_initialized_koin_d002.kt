// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58 guard: a `val` initialized with `startKoin { }.koin` / `koinApplication { }.koin` holds
// this compilation's own Koin, so its call sites are still judged.
//
// EXPECTED: KOIN-D002 for `get<Missing>()` and `get<AlsoMissing>()`.
package testpkg

import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class LocalThing
class Missing
class AlsoMissing

val appKoin: Koin = startKoin { modules(module { single<LocalThing>() }) }.koin

fun load(): Missing = appKoin.get<Missing>()

class Holder {
    val koin = koinApplication { modules(module { single<LocalThing>() }) }.koin
    fun load(): AlsoMissing = koin.get<AlsoMissing>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, propertyDeclaration */
