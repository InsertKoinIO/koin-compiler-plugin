// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Precision test for a local val (inside a top-level function's body, not box() itself, so the
// enclosing module keeps a real id): fully resolves, so a genuine, unrelated missing dependency is
// caught as a hard KOIN-D001, not swallowed into a KOIN-W003-only disclosure.
package testpkg

import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Missing
class NeedsMissing(val m: Missing)

fun repoModule() = module { single<Repository>() }

fun appModule(): Module {
    val extras = listOf(repoModule())
    return module {
        includes(extras)
        single<NeedsMissing>()
    }
}

fun useIt() {
    koinApplication { modules(appModule()) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty,
   primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
