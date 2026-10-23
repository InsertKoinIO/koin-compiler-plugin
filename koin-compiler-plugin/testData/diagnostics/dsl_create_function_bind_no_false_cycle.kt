// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Companion to dsl_bind_narrowed_dependency_no_false_cycle: the SAFE-DSL rewrite of the same
// MeteredBlobStore decorator shape, using create(::function) instead of a raw manual lambda so
// the plugin (not the user) generates the get() call and its type is unambiguous.
//
// single { create(::buildMeteredBlobStore) } bind BlobStore::class
//   buildMeteredBlobStore(os: DiskBlobStore): MeteredBlobStore
//
// EXPECTED: completely silent. buildMeteredBlobStore's own parameter is DiskBlobStore
// — a distinct, present definition — so there is no cycle, even though this same DSL definition
// also binds BlobStore (MeteredBlobStore's declared supertype).
package testpkg

import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

interface BlobStore
class DiskBlobStore : BlobStore
class MeteredBlobStore(private val delegate: BlobStore) : BlobStore

fun buildMeteredBlobStore(os: DiskBlobStore): MeteredBlobStore = MeteredBlobStore(os)

val appModule = module {
    single<DiskBlobStore>()
    single { create(::buildMeteredBlobStore) } bind BlobStore::class
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration,
   lambdaLiteral, primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
