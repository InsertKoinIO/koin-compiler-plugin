// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Decorator shape found in a real downstream app:
// a class binds an interface via `bind` AND depends on that SAME interface through its own
// constructor, but the actual dependency resolved inside the lambda body is a NARROWER type
// (`get<DiskBlobStore>()`), not the constructor parameter's static declared type
// (`BlobStore`).
//
// Requirements for a hand-written `single { ... }` lambda body were derived from the target
// class's CONSTRUCTOR PARAMETER TYPES, not from the actual `get<T>()` calls the lambda makes.
// Since `MeteredBlobStore`'s constructor parameter is statically typed `BlobStore`,
// and this same definition also `bind`s `BlobStore`, the wrongly-derived requirement
// resolved back onto the definition's OWN node — a false self-loop:
//   KOIN-D004: MeteredBlobStore -> MeteredBlobStore
//
// EXPECTED: no diagnostics at all. MeteredBlobStore depends on DiskBlobStore, a
// distinct definition — there is no cycle, and get<DiskBlobStore>() resolves cleanly as its
// own tracked call site. (KOIN-W007 used to fire here unconditionally for any opaque lambda body;
// removed once call-site validation started covering a lambda's own get() calls directly — see
// docs/COMPILE_TIME_SAFETY.md for the one residual gap, KOIN-D004 cycle detection THROUGH an
// opaque lambda, which this test doesn't exercise since there's genuinely no cycle here.)
package testpkg

import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface BlobStore
class DiskBlobStore : BlobStore
class MeteredBlobStore(private val delegate: BlobStore) : BlobStore

val appModule = module {
    single<DiskBlobStore>()
    single { MeteredBlobStore(get<DiskBlobStore>()) } bind BlobStore::class
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration,
   lambdaLiteral, primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
