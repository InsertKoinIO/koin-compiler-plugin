// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58 guard: the receiver IS traceable to this compilation's entry point
// (`koinApplication { }` held in a val, then `.koin`), so the call site is still judged.
//
// EXPECTED: KOIN-D002 for `get<Missing>()`.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Missing
class LocalThing

fun useIt() {
    val app = koinApplication {
        modules(module { single<LocalThing>() })
    }
    val missing = app.koin.get<Missing>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty, propertyDeclaration */
