// Cross-module counterpart of dsl_bind_narrowed_dependency_no_false_cycle (testData/diagnostics):
// same decorator shape (MeteredBlobStore, found in a real downstream
// app), but the DSL definition and the koinApplication entry point live in DIFFERENT modules.
//
// A DIFFERENT code path builds the graph here: the entry point module doesn't see the DSL
// definition's IR directly, it reconstructs it from a synthetic hint function
// (DslHintGenerator.discoverDslDefinitionsFromHints). That reconstruction unconditionally
// re-derived `requirements` from the target class's constructor for EVERY discovered DSL
// definition, including providerOnly (hand-written lambda) ones — ignoring the already-decoded
// providerOnly flag. That is the same bug the same-module fix (KoinDSLTransformer.kt) covers,
// but a SEPARATE occurrence: fixing the same-module collection path alone left this cross-module
// path still producing a false KOIN-D004 self-cycle on `MeteredBlobStore`.
//
// RED signal: before the DslHintGenerator fix, this module fails to COMPILE (KOIN-D004), so the
// regression guard is compilation success itself, not a runtime assertion.

// MODULE: core
// FILE: blobstore.kt
package com.example.blobstore

import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface BlobStore
class DiskBlobStore : BlobStore
class MeteredBlobStore(private val delegate: BlobStore) : BlobStore

fun blobStoreModule() = module {
    single<DiskBlobStore>()
    single { MeteredBlobStore(get<DiskBlobStore>()) } bind BlobStore::class
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
