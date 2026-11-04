// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58: a scope taken from an untraced `Koin` is as unknown as the Koin itself.
//
// EXPECTED: no KOIN-D002 for `get<Missing>()`, one KOIN-W009 instead.
package testpkg

import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class LocalThing
class Missing

fun main() {
    startKoin { modules(module { single<LocalThing>() }) }
}

fun renderScope(koin: Koin): Missing = koin.getScope("s").get<Missing>()

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, stringLiteral */
