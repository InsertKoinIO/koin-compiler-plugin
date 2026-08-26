// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Precision test for a + b (list concatenation): fully resolves, so a genuine, unrelated missing
// dependency is caught as a hard KOIN-D001, not swallowed into a KOIN-W003-only disclosure.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Other
class Missing
class NeedsMissing(val m: Missing)

fun repoModule() = module { single<Repository>() }
val otherModule = module { single<Other>() }

val leftModules = listOf(repoModule())
val rightModules = listOf(otherModule)

val appModule = module {
    includes(leftModules + rightModules)
    single<NeedsMissing>()
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor,
   propertyDeclaration, topLevelPropertyDeclaration */
