// Third variant of the FileStorageModule/InstrumentedStoreService decorator bug, found live in a
// real downstream rebuild: the SAFE-DSL rewrite (create(::function) instead of a hand-written
// lambda) still false-cycled cross-module, because DslHintGenerator's cross-module reconstruction
// re-derived requirements from the RETURN TYPE's constructor regardless of whether the definition
// came from that type's own constructor or from an unrelated referenced function.
//
// single { create(::buildInstrumentedStoreService) } bind StoreService::class
//   fun buildInstrumentedStoreService(os: ObjectStoreService): InstrumentedStoreService
//
// Locally this correctly derives requirements from buildInstrumentedStoreService's OWN parameter
// (ObjectStoreService) — see KoinDSLTransformer.requirementsFor. But the cross-module hint only
// carried the return type (InstrumentedStoreService), and the consumer re-guessed requirements
// from ITS constructor (delegate: StoreService) — the same type this definition also `bind`s —
// producing the identical false KOIN-D004 self-cycle via a different DSL form.
//
// RED signal: before the fix (encoding real requirement types in the hint instead of guessing),
// this module fails to COMPILE (KOIN-D004).

// MODULE: core
// FILE: filestore.kt
package io.kotzilla.ingestion.filestore.service

import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

interface StoreService
class ObjectStoreService : StoreService
class InstrumentedStoreService(private val delegate: StoreService) : StoreService

fun buildInstrumentedStoreService(os: ObjectStoreService): InstrumentedStoreService = InstrumentedStoreService(os)

fun fileStorageModule() = module {
    single<ObjectStoreService>()
    single { create(::buildInstrumentedStoreService) } bind StoreService::class
}

// MODULE: app(core)
// FILE: test.kt
import io.kotzilla.ingestion.filestore.service.StoreService
import io.kotzilla.ingestion.filestore.service.fileStorageModule
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

// Phase 3.1 (DSL-only A3 validation, which is what discovers cross-module DSL hints for a plain
// koinApplication { } entry point) only runs when THIS module has at least one local DSL
// definition of its own — a marker class here is enough to satisfy that gate.
class Marker

fun box(): String {
    val koin = koinApplication {
        modules(module { single<Marker>() }, fileStorageModule())
    }.koin
    koin.get<StoreService>()
    koin.get<Marker>()
    return "OK"
}
