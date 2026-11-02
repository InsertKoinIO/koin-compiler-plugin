// RUN_PIPELINE_TILL: BACKEND
// Review: an unloaded dependency helper's KOIN-W001 must not tell the user to load the helper itself.
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
    connectionBeans()
}

private fun Module.connectionBeans() {
    single<Invoicing>()
}

// MODULE: app(feat)
// FILE: app/Scripts.kt
package app

import feat.alertModule
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Database

fun main() {
    startKoin { modules(module { single<Database>() }, alertModule()) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
