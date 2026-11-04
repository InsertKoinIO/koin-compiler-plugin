// RUN_PIPELINE_TILL: BACKEND
// Review: a dependency's helper called from the consumer's own module val is loaded through that val.
// MODULE: lib
// FILE: lib/Helpers.kt
package lib

import org.koin.core.module.Module
import org.koin.plugin.module.dsl.single

class Store
class Invoicing(val store: Store)

fun Module.invoicingBeans() {
    single<Invoicing>()
}

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.Invoicing
import lib.invoicingBeans
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Consumer(val t: Invoicing)

val appModule = module {
    invoicingBeans()
    single<Consumer>()
}

fun main() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
