// Third variant of the MeteredBlobStore decorator bug, found live in a
// real downstream rebuild: the SAFE-DSL rewrite (create(::function) instead of a hand-written
// lambda) still false-cycled cross-module, because DslHintGenerator's cross-module reconstruction
// re-derived requirements from the RETURN TYPE's constructor regardless of whether the definition
// came from that type's own constructor or from an unrelated referenced function.
//
// single { create(::buildMeteredBlobStore) } bind BlobStore::class
//   fun buildMeteredBlobStore(os: DiskBlobStore): MeteredBlobStore
//
// Locally this correctly derives requirements from buildMeteredBlobStore's OWN parameter
// (DiskBlobStore) — see KoinDSLTransformer.requirementsFor. But the cross-module hint only
// carried the return type (MeteredBlobStore), and the consumer re-guessed requirements
// from ITS constructor (delegate: BlobStore) — the same type this definition also `bind`s —
// producing the identical false KOIN-D004 self-cycle via a different DSL form.
//
// RED signal: before the fix (encoding real requirement types in the hint instead of guessing),
// this module fails to COMPILE (KOIN-D004).

// MODULE: core
// FILE: blobstore.kt
package com.example.blobstore

import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

interface BlobStore
class DiskBlobStore : BlobStore
class MeteredBlobStore(private val delegate: BlobStore) : BlobStore

fun buildMeteredBlobStore(os: DiskBlobStore): MeteredBlobStore = MeteredBlobStore(os)

fun blobStoreModule() = module {
    single<DiskBlobStore>()
    single { create(::buildMeteredBlobStore) } bind BlobStore::class
}

// MODULE: app(core)
// FILE: test.kt
import com.example.blobstore.BlobStore
import com.example.blobstore.blobStoreModule
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

// Phase 3.1 (DSL-only A3 validation, which is what discovers cross-module DSL hints for a plain
// koinApplication { } entry point) only runs when THIS module has at least one local DSL
// definition of its own — a marker class here is enough to satisfy that gate.
class Marker

fun box(): String {
    val koin = koinApplication {
        modules(module { single<Marker>() }, blobStoreModule())
    }.koin
    koin.get<BlobStore>()
    koin.get<Marker>()
    return "OK"
}
