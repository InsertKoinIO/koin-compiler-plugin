// Cross-module counterpart of dsl_singleof_narrowed_dependency_no_false_cycle (testData/diagnostics),
// mirroring cross_module_dsl_create_function_narrowed_dependency_no_false_cycle for the
// constructor-shorthand DSL: singleOf's requirements are now derived (see
// KoinDSLTransformer.collectConstructorShorthandDef) and encoded into the cross-module hint via
// the same req0_/reqsEncoded mechanism create(::function) already uses (966d09a) — this proves
// that encoding path doesn't reintroduce the wrong-source-of-requirements bug for singleOf
// specifically.
//
// singleOf(::buildInstrumentedStoreService) { bind<StoreService>() }
//   fun buildInstrumentedStoreService(os: ObjectStoreService): InstrumentedStoreService
//
// Locally this correctly derives requirements from buildInstrumentedStoreService's OWN parameter
// (ObjectStoreService) — see KoinDSLTransformer.requirementsFor. The cross-module hint must carry
// that same real requirement, not re-guess from InstrumentedStoreService's constructor (delegate:
// StoreService — the same type this definition also `bind`s), which would false-cycle KOIN-D004.

// MODULE: core
// FILE: filestore.kt
package io.kotzilla.ingestion.filestore.service

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface StoreService
class ObjectStoreService : StoreService
class InstrumentedStoreService(private val delegate: StoreService) : StoreService

fun buildInstrumentedStoreService(os: ObjectStoreService): InstrumentedStoreService = InstrumentedStoreService(os)

fun fileStorageModule() = module {
    single<ObjectStoreService>()
    singleOf(::buildInstrumentedStoreService) { bind<StoreService>() }
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
