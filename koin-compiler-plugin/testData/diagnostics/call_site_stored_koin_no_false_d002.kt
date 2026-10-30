// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58, known limit: a Koin stored in a `lateinit var` (or behind a custom getter, `by lazy`, a
// helper) has the same shape whether it holds this app's Koin or a test helper's. Its graph is
// unknown here, so its call sites are not judged; KOIN-W009 discloses them.
//
// EXPECTED: no KOIN-D002 for `get<Missing>()`, one KOIN-W009 instead.
package testpkg

import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class LocalThing
class Missing

object Di {
    lateinit var koin: Koin
}

fun main() {
    Di.koin = startKoin { modules(module { single<LocalThing>() }) }.koin
}

fun load(): Missing = Di.koin.get<Missing>()

/* GENERATED_FIR_TAGS: assignment, classDeclaration, functionDeclaration, lambdaLiteral, lateinit, objectDeclaration,
propertyDeclaration */
