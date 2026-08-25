// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Phase-3.1 (DSL-only) analogue of entry_dynamic_modules_missing.kt: a dynamically-computed module
// set (unresolvable `modules(extras)`) plus a genuine, unrelated local miss (NeedsMissing needs
// Missing, provided nowhere). The typed startKoin<T>() path already withholds KOIN-D001
// unconditionally when dynamic; this pins the same for validateDslDefinitionGraph.
//
// EXPECTED: only KOIN-W003. No KOIN-D001 for NeedsMissing's Missing parameter.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)
class Missing
class NeedsMissing(val m: Missing)

val coreModule = module {
    single<Repository>()
}

val appModule = module {
    single<Service>()
    single<NeedsMissing>()
}

val extras = listOf(coreModule)

fun useIt() {
    val koin = koinApplication {
        modules(appModule)
        modules(extras)
    }.koin
    val repo = koin.get<Repository>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty,
   primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
