// RUN_PIPELINE_TILL: BACKEND
// Guard: when the module calling a `fun Module.helper()` IS loaded, the helper's
// definitions are validated — a missing dependency still fails, including through a nested helper.
//
// Topology:
//   feat : invoicingModule() { connectionBeans() }
//          private fun Module.connectionBeans() { single<Invoicing>(); creationBeans() }   Invoicing needs TicketStore (nowhere)
//          private fun Module.creationBeans()   { single<Creator>() }                      Creator needs CreatorStore (nowhere)
//   app  : koinApplication { modules(module { single { Database() } }, invoicingModule()) }
//
// EXPECTED: KOIN-D001 for TicketStore and for CreatorStore.

// MODULE: feat
// FILE: feat/Capabilities.kt
package feat

import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class TicketStore
class Invoicing(val store: TicketStore)
class CreatorStore
class Creator(val store: CreatorStore)

fun invoicingModule() = module {
    connectionBeans()
}

private fun Module.connectionBeans() {
    single<Invoicing>()
    creationBeans()
}

private fun Module.creationBeans() {
    single<Creator>()
}

// MODULE: app(feat)
// FILE: app/Scripts.kt
package app

import feat.invoicingModule
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class Database

fun main() {
    // The inline module gives this compilation a DSL definition of its own, so the DSL graph is validated here.
    koinApplication { modules(module { single { Database() } }, invoicingModule()) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
