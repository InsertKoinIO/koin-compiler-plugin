// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58: `!!` doesn't hide where the scope comes from: a `Koin` received as a parameter.
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

fun renderScope(koin: Koin): Missing = koin.getScopeOrNull("s")!!.get<Missing>()

/* GENERATED_FIR_TAGS: checkNotNullCall, classDeclaration, functionDeclaration, lambdaLiteral, nullableType,
stringLiteral */
