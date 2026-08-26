// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Decorator shape found in a real downstream app (Kotzilla server, FileStorageModule):
// a class binds an interface via `bind` AND depends on that SAME interface through its own
// constructor, but the actual dependency resolved inside the lambda body is a NARROWER type
// (`get<ObjectStoreService>()`), not the constructor parameter's static declared type
// (`StoreService`).
//
// Requirements for a hand-written `single { ... }` lambda body were derived from the target
// class's CONSTRUCTOR PARAMETER TYPES, not from the actual `get<T>()` calls the lambda makes.
// Since `InstrumentedStoreService`'s constructor parameter is statically typed `StoreService`,
// and this same definition also `bind`s `StoreService`, the wrongly-derived requirement
// resolved back onto the definition's OWN node — a false self-loop:
//   KOIN-D004: InstrumentedStoreService -> InstrumentedStoreService
//
// EXPECTED: completely silent. InstrumentedStoreService depends on ObjectStoreService, a
// distinct definition — there is no cycle.
package testpkg

import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface StoreService
class ObjectStoreService : StoreService
class InstrumentedStoreService(private val delegate: StoreService) : StoreService

val appModule = module {
    single<ObjectStoreService>()
    single { InstrumentedStoreService(get<ObjectStoreService>()) } bind StoreService::class
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration,
   lambdaLiteral, primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
