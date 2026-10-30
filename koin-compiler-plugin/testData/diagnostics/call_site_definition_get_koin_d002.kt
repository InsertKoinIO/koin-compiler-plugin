// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58 guard: `getKoin()` inside a definition lambda is the definition's own Koin, so the call
// site is still judged.
//
// EXPECTED: KOIN-D002 for `get<Missing>()`.
package testpkg

import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Missing
class Holder(val missing: Missing)

fun main() {
    startKoin { modules(module { single<Holder> { Holder(getKoin().get<Missing>()) } }) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
