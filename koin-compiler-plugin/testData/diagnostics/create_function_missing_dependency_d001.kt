// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// single { create(::function) } must validate the referenced FUNCTION's own parameters, not the
// returned class's constructor. ApiProvider (the return type) has zero constructor params —
// requirementsForClass(ApiProvider) derives [], silently missing the genuinely-missing MissingDep
// that provideApi() itself requires. Sibling bug to the fixed singleOf(::function) case: same
// wrong-source-of-requirements pattern, previously also masked here by providerOnly=true excluding
// this def from validation entirely.
//
// EXPECTED: KOIN-D001 for MissingDep — proves both the requirement-source fix and dropping
// providerOnly actually engage validation for create(::function).
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

class MissingDep
class ApiProvider

fun provideApi(dep: MissingDep): ApiProvider = ApiProvider()

val appModule = module {
    single { create(::provideApi) }
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, functionDeclaration, lambdaLiteral, propertyDeclaration */
