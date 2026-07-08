// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Repro for KTZ-4256 / GH koin-compiler-plugin#51.
//
// Two sibling @Module classes, DataModule and FeatureModule, both listed in the app's
// @KoinApplication(modules = [DataModule, FeatureModule]). Service (declared by FeatureModule)
// depends on Repository, which is provided by the *sibling* DataModule.
//
// Before the fix, A2 per-module validation checked FeatureModule in isolation — it saw only its
// own definition (Service), not DataModule's Repository — and falsely reported
// KOIN-D001 Missing dependency: Repository. There is NO real missing dependency: at the
// @KoinApplication entry point both modules are assembled and the graph is complete, so the
// dep resolves at A3 and NO diagnostic must fire (empty .errors.txt).
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.annotation.Factory
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

class Repository

class Service(val repo: Repository)

// DataModule provides Repository
@Module
class DataModule {
    @Single
    fun repository(): Repository = Repository()
}

// FeatureModule provides Service, which needs Repository from the sibling DataModule
@Module
class FeatureModule {
    @Factory
    fun service(repo: Repository): Service = Service(repo)
}

@KoinApplication(modules = [DataModule::class, FeatureModule::class])
object MyApp

// The startKoin<MyApp>() call is the complete closed closure: A3 assembles DataModule +
// FeatureModule and the Service -> Repository dependency resolves across the sibling modules.
fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, objectDeclaration,
   primaryConstructor, propertyDeclaration */
