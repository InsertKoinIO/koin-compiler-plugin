// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Falsify-first control for dsl_new_constructor_missing_dependency_d001: a COMPLETE graph using
// new(::Ctor) must stay silent — proves recognizing new(::T) doesn't itself introduce a false
// positive (e.g. by double-registering the definition, or by not resolving Dep).
//
// EXPECTED: completely silent. Any diagnostic here is a false positive.
package testpkg

import org.koin.core.module.dsl.new
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Dep
class Repo(val dep: Dep)

val appModule = module {
    single<Dep>()
    single { new(::Repo) }
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, functionDeclaration, lambdaLiteral,
   primaryConstructor, propertyDeclaration */
