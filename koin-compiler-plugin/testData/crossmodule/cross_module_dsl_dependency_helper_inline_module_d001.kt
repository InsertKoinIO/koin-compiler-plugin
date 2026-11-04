// RUN_PIPELINE_TILL: BACKEND
// Review: a dependency's fun Module.helper() called from an inline module { } at the entry point is loaded; its missing dependency must still be KOIN-D001 (was silently dropped).
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

import lib.invoicingBeans
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Other

fun main() {
    koinApplication { modules(module { invoicingBeans(); single<Other>() }) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
