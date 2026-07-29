// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression guard — a genuinely missing cross-module dependency must still hard-error.
//
// FeatureModule.service() needs Repository, but NO module provides Repository anywhere on the graph
// (DataModule provides only Unrelated). Caught as an authoritative KOIN-D001 ERROR at A3 (the
// startKoin<MyApp> entry point below) — A2's old per-module pass is gone (1.1.0), so this is now
// the ONLY place a cross-module miss like this can be caught. No entry point ⇒ this would go
// silent instead (see CompileSafetyValidator's class doc).
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
