// RUN_PIPELINE_TILL: BACKEND
// definitions declared in a `fun Module.helper()` belong to the modules that call the
// helper. A consumer that never loads those modules must not validate them.
//
// Topology:
//   feat : alertModule()     { single<Links>() }                                   <-- loaded
//          invoicingModule() { includes(alertModule()); connectionBeans() }        <-- NOT loaded
//          private fun Module.connectionBeans() { single<Invoicing>() }            Invoicing needs TicketStore (nowhere)
//   app  : koinApplication { modules(module { single { Database() } }, alertModule()) }
//
// EXPECTED: no KOIN-D001 for Invoicing's TicketStore — its module is never loaded.

// MODULE: feat
// FILE: feat/Capabilities.kt
package feat

import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Links
class TicketStore
class Invoicing(val store: TicketStore)

fun alertModule() = module {
    single<Links>()
}

fun invoicingModule() = module {
    includes(alertModule())
    connectionBeans()
}

private fun Module.connectionBeans() {
    single<Invoicing>()
}

// MODULE: app(feat)
// FILE: app/Scripts.kt
package app

import feat.alertModule
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class Database

fun main() {
    // The inline module gives this compilation a DSL definition of its own, so the DSL graph is validated here.
    koinApplication { modules(module { single { Database() } }, alertModule()) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
