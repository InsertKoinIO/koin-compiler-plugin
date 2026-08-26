// Regression for a real downstream break: the app-dsl playground's DispatchersModule failed to
// compile after the requirement-encoding fix (see cross_module_dsl_create_function_missing_d001)
// landed, with a false KOIN-D001 for an UNQUALIFIED CoroutineDispatcher even though a QUALIFIED
// one (@Named-equivalent) was present — because buildRequirementParams encoded only each
// requirement's TYPE, dropping its qualifier entirely. Real shape:
//
//   fun coroutineScope(@Dispatcher(NiaDispatchers.Default) default: CoroutineDispatcher) = ...
//   single { create(::dispatcherDefault) }   // provides a QUALIFIED CoroutineDispatcher
//   single { create(::coroutineScope) }      // requires that SAME qualifier
//
// Minimized here with the built-in @Named instead of a custom qualifier meta-annotation (the
// meta-annotation resolution itself isn't what this hint-encoding bug is about).
//
// Topology: lib provides a qualified Dep and a create(::function) consumer that requires it by
// that same qualifier; app has its own local DSL def (to satisfy the Phase 3.1 gate — a plain
// koinApplication { } entry point only validates cross-module DSL hints when this module has at
// least one local definition of its own) and pulls in lib.
//
// EXPECTED: compiles and resolves — no false KOIN-D001 for the qualified dependency.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.Named
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.plugin.module.dsl.create

class Dep
class Client(val dep: Dep)

fun provideQualifiedDep(): Dep = Dep()
fun buildClient(@Named("q") dep: Dep): Client = Client(dep)

val libModule = module {
    single(qualifier = named("q")) { create(::provideQualifiedDep) }
    single { create(::buildClient) }
}

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.Client
import lib.libModule
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Marker

fun box(): String {
    val koin = koinApplication {
        modules(module { single<Marker>() }, libModule)
    }.koin
    koin.get<Client>()
    koin.get<Marker>()
    return "OK"
}
