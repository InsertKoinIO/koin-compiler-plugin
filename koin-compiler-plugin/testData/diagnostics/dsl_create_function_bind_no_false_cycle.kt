// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Companion to dsl_bind_narrowed_dependency_no_false_cycle: the SAFE-DSL rewrite of the same
// FileStorageModule decorator shape, using create(::function) instead of a raw manual lambda so
// the plugin (not the user) generates the get() call and its type is unambiguous.
//
// single { create(::buildInstrumentedStoreService) } bind StoreService::class
//   buildInstrumentedStoreService(os: ObjectStoreService): InstrumentedStoreService
//
// EXPECTED: completely silent. buildInstrumentedStoreService's own parameter is ObjectStoreService
// — a distinct, present definition — so there is no cycle, even though this same DSL definition
// also binds StoreService (InstrumentedStoreService's declared supertype).
package testpkg

import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

interface StoreService
class ObjectStoreService : StoreService
class InstrumentedStoreService(private val delegate: StoreService) : StoreService

fun buildInstrumentedStoreService(os: ObjectStoreService): InstrumentedStoreService = InstrumentedStoreService(os)

val appModule = module {
    single<ObjectStoreService>()
    single { create(::buildInstrumentedStoreService) } bind StoreService::class
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration,
   lambdaLiteral, primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
