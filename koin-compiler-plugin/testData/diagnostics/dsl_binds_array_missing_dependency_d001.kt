// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Companion to dsl_binds_array_valid_graph_ok: proves recognizing `binds(arrayOf(...))` doesn't
// over-correct into hiding a genuinely missing dependency. PortB is required but bound by nobody.
//
// EXPECTED: KOIN-D001 for PortB only — PortA (bound via `binds`) must still resolve cleanly.
package testpkg

import org.koin.dsl.binds
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface PortA
interface PortB
class RepoImpl : PortA
class ConsumerA(val port: PortA)
class ConsumerB(val port: PortB)

val appModule = module {
    single<RepoImpl>() binds arrayOf(PortA::class)
    single<ConsumerA>()
    single<ConsumerB>()
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration,
   lambdaLiteral, primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
