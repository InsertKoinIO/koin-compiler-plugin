// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Precision test for arrayOf(...).toList(): fully resolves, so a genuine, unrelated missing
// dependency is caught as a hard KOIN-D001, not swallowed into a KOIN-W003-only disclosure.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Missing
class NeedsMissing(val m: Missing)

fun repoModule() = module { single<Repository>() }

val appModule = module {
    includes(arrayOf(repoModule()).toList())
    single<NeedsMissing>()
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor,
   propertyDeclaration, topLevelPropertyDeclaration */
