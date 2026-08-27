// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Falsify-first control for Koin's multi-binding `binds(arrayOf(...))` — the plural, vararg-array
// counterpart of `bind` (single interface). KoinDSLTransformer only ever recognized `bind` (singular);
// `binds` was never wired up at all, so a definition bound ONLY via `binds(...)` silently carried ZERO
// bindings — every consumer of every one of its bound interfaces got a false KOIN-D001, no matter how
// many hops away it lived. Real-world shape (Kotzilla server): `single<AppRepository>() binds
// arrayOf(AppPersistencePort::class, AppRepository::class)`.
//
// EXPECTED: completely silent. Consumer resolves BOTH PortA and PortB, which nothing provides except
// via `binds`. Any diagnostic here is a false positive.
package testpkg

import org.koin.dsl.binds
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface PortA
interface PortB
class RepoImpl : PortA, PortB
class ConsumerA(val port: PortA)
class ConsumerB(val port: PortB)

val appModule = module {
    single<RepoImpl>() binds arrayOf(PortA::class, PortB::class)
    single<ConsumerA>()
    single<ConsumerB>()
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration,
   lambdaLiteral, primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
