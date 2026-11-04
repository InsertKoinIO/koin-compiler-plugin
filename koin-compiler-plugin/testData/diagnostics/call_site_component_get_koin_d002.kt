// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58 guard: `getKoin()` inside a KoinComponent is Koin's own accessor (a fake override of
// `KoinComponent.getKoin`), so where `startKoin` is called the call site is still judged.
//
// EXPECTED: KOIN-D002 for `get<Missing>()`.
package testpkg

import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class LocalThing
class Missing

fun main() {
    startKoin { modules(module { single<LocalThing>() }) }
}

class Screen : KoinComponent {
    fun load(): Missing = getKoin().get<Missing>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral */
