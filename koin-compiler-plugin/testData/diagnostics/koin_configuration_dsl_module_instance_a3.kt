// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Issue #38 (follow-up to KTZ-4037): the Compose entry
// `KoinApplication(configuration = koinConfiguration { modules(appModule) })` loads a DSL
// `module { }` *instance* (not a @Module class). collectModuleClassesFromLambda only collects
// @Module CLASSES from `modules(KClass)`, so for an instance-loaded DSL module the entry's
// full-graph (A3) validation was skipped and a missing dependency compiled cleanly (crashing at
// runtime). Here `Service` needs `Repo`, which is never registered — A3 must fire
// `[Koin][KOIN-D001] Missing dependency: testpkg.Repo`.
package testpkg

import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repo
class Service(val repo: Repo)

val appModule = module {
    single<Service>()
}

fun setup() {
    koinConfiguration {
        modules(appModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
