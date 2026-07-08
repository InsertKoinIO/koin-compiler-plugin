// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// KTZ-4256 regression guard — the fix must NOT neuter real missing-dependency detection.
//
// FeatureModule.service() needs Repository, but NO module provides Repository anywhere on the graph
// (DataModule provides only Unrelated). The KTZ-4256 discriminator keys off provider-hint EXISTENCE:
// since no provider hint for Repository exists anywhere, the miss is genuine and is caught as an
// authoritative KOIN-D001 ERROR at A2 (module: FeatureModule) — NOT deferred, NOT downgraded to the
// KOIN-W002 warning. (Contrast the closure-state PoC, which deferred every A2 miss and only surfaced
// this at A3's startKoin<MyApp> closure; the hint-existence design catches it earlier and precisely.)
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.annotation.Factory
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

class Repository

class Service(val repo: Repository)

class Unrelated

// DataModule provides Unrelated — NOT Repository.
@Module
class DataModule {
    @Single
    fun unrelated(): Unrelated = Unrelated()
}

// FeatureModule needs Repository, which no module provides anywhere in the graph.
@Module
class FeatureModule {
    @Factory
    fun service(repo: Repository): Service = Service(repo)
}

@KoinApplication(modules = [DataModule::class, FeatureModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, objectDeclaration,
   primaryConstructor, propertyDeclaration */
