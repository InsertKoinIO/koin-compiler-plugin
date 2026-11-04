// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression: singleOf(::fn) { bind<Interface>() } must derive requirements from the referenced
// function's OWN parameters, not from the bind target or the returned class's constructor — the
// same bug class already fixed once for create(::function) (cdbba09) and for hand-written lambda
// bodies (e71f159, see dsl_bind_narrowed_dependency_no_false_cycle). Now that singleOf's
// requirements are derived again (see KoinDSLTransformer.collectConstructorShorthandDef /
// requirementsFor), this exact shape needs its own falsify-first coverage: MeteredBlobStore's
// constructor parameter is statically typed BlobStore — the SAME interface this definition also
// binds — but the function actually referenced (buildMetered) depends on the NARROWER
// DiskBlobStore. A wrongly-derived requirement (from the constructor/bind target instead of
// buildMetered's own parameter) would resolve back onto this definition's own bind-aliased
// graph node — a false KOIN-D004 self-cycle.
//
// EXPECTED: no diagnostics. MeteredBlobStore depends on DiskBlobStore, a distinct
// definition — there is no cycle.
package testpkg

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface BlobStore
class DiskBlobStore : BlobStore
class MeteredBlobStore(private val delegate: BlobStore) : BlobStore

fun buildMetered(os: DiskBlobStore): MeteredBlobStore = MeteredBlobStore(os)

val appModule = module {
    single<DiskBlobStore>()
    singleOf(::buildMetered) { bind<BlobStore>() }
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral,
   primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
