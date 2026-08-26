// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// singleOf(::Ctor) must derive real requirements from the constructor, not just be "present but
// unchecked" — a genuine missing dependency must still hard-fail KOIN-D001. This is the case that
// would NOT have been caught by disclosure alone: only requirement derivation catches it.
package testpkg

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class Missing
class NeedsMissing(val m: Missing)

val appModule = module {
    singleOf(::NeedsMissing)
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor,
   propertyDeclaration, topLevelPropertyDeclaration */
