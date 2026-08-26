// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Precision test for a function returning List<Module> via a single-statement body: fully resolves
// (follows the body), so a genuine, unrelated missing dependency is caught as a hard KOIN-D001, not
// swallowed into a KOIN-W003-only disclosure.
package testpkg

import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Missing
class NeedsMissing(val m: Missing)

fun repoModule() = module { single<Repository>() }

fun coreModules(): List<Module> = listOf(repoModule())

val appModule = module {
    includes(coreModules())
    single<NeedsMissing>()
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor,
   propertyDeclaration, topLevelPropertyDeclaration */
