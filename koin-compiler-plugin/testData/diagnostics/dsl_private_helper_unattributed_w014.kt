// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// A private `Module.helper()` whose definitions compile safety cannot attribute to a module
//: called on an explicit receiver, or referenced as a function. Correct Koin
// code; nothing is missing.
//
// EXPECTED: one KOIN-W014 at the explicit-receiver call and one at the function reference,
// each naming the rewrite. No other diagnostic.
package testpkg

import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Store
class Creator(val store: Store)
class Links

private fun Module.beans() {
    single<Creator>()
}

val loadedModule = module {
    single<Store>()
    single<Links>()
}

val wiringModule = module {
    loadedModule.beans()
}

val refModule = module(moduleDeclaration = Module::beans)

fun main() {
    startKoin { modules(loadedModule) }
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
