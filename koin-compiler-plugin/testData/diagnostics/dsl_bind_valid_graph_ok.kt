// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Falsify-first control for the `bind` retention fix: a COMPLETE graph must stay silent.
//
// dsl_bind_missing_dependency_d001 proves requirements survive copy(). On its own that is only half
// the story — a fix that made copy() drop the BINDING instead, or that double-registered the
// definition, would still emit the expected D001 there while breaking correct code here.
//
// This graph resolves only if BOTH halves of the copy survive:
//  - the binding (primary-constructor property) — Consumer resolves UserRepository, which nothing
//    provides except via `bind`;
//  - the requirements (body property) — UserRepositoryImpl's own dep is present, so no D001.
//
// EXPECTED: completely silent. Any diagnostic here is a false positive.
package testpkg

import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface UserRepository
class ApiService
class UserRepositoryImpl(val api: ApiService) : UserRepository
class Consumer(val repo: UserRepository)

val appModule = module {
    single<ApiService>()
    single<UserRepositoryImpl>() bind UserRepository::class
    single<Consumer>()
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration,
   lambdaLiteral, primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
