// RUN_PIPELINE_TILL: BACKEND
// Guard (the ticket's first-guess shape, already fine on 1.2.2): an entry point loading an
// inline `module { }` next to some module functions of a dependency must not validate the dependency's
// OTHER module functions, which it never loads. The real cause was `fun Module.helper()` definitions.
//
// Topology:
//   feat : one file, three module functions
//          reportModule()     { single<Rehash>() }                 <-- loaded
//          alertModule()      { single<Links>() }                  <-- loaded
//          invoicingModule()  { single<Invoicing>() }              <-- NOT loaded; Invoicing needs TicketStore,
//                                                                      provided by a module this app never loads
//   app  : koinApplication { modules(toolkitModules(database)) }, toolkitModules = listOf(module { single { database } }, reportModule(), alertModule())
//
// EXPECTED: no KOIN-D001 for Invoicing / TicketStore (its module is never loaded).

// MODULE: feat
// FILE: feat/Capabilities.kt
package feat

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Database
class Rehash(val db: Database)
class Links(val db: Database)
class TicketStore
class Invoicing(val store: TicketStore, val db: Database)

fun reportModule() = module {
    single<Rehash>()
}

fun alertModule() = module {
    single<Links>()
}

fun invoicingModule() = module {
    single<Invoicing>()
}

// MODULE: app(feat)
// FILE: app/Scripts.kt
package app

import feat.Database
import feat.alertModule
import feat.reportModule
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

// Real shape: the loaded set comes from a helper with a parameter, returning listOf(...) with an
// inline module first, and the entry point sits inside a lambda.
internal fun toolkitModules(database: Database): List<Module> = listOf(
    module { single { database } },
    reportModule(),
    alertModule(),
)

fun command(build: (Database) -> Koin): Koin = build(Database())

fun main() {
    command { database -> koinApplication { modules(toolkitModules(database)) }.koin }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, lambdaLiteral, primaryConstructor,
propertyDeclaration */
