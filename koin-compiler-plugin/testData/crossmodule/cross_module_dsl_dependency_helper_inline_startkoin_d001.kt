// RUN_PIPELINE_TILL: BACKEND
// Review: same with startKoin and a consumer of the helper's definition: no false D001/W001 for the loaded def, real D001 kept.
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
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Consumer(val t: Invoicing)

fun main() {
    startKoin { modules(module { invoicingBeans(); single<Consumer>() }) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
