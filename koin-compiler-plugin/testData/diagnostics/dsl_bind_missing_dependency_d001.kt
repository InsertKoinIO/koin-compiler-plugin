// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// A DSL definition with `bind` must still have its constructor dependencies validated.
//
// `requirements` is attached at COLLECTION time (A3 metadata contract) and lives as a mutable BODY
// property on the Definition base class, deliberately kept out of the primary constructor so that
// data-class equals/hashCode/dedup stay unchanged. But `copy()` on a data class re-runs the PRIMARY
// constructor, so every body property resets to its default — and `collectBindType` rebuilds the
// definition via `lastDef.copy(bindings = …)` when it sees `bind`.
//
// If that wipes `requirements`, the verifier reads an empty list, iterates zero requirements, and a
// missing constructor dependency compiles green then throws NoDefinitionFoundException at runtime —
// the worst failure class. `origin` would be lost the same way, degrading D001 attribution.
//
// EXPECTED: KOIN-D001 for ApiService. Identical to the same definition written without `bind`.
package testpkg

import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface UserRepository
class ApiService
class UserRepositoryImpl(val api: ApiService) : UserRepository

val appModule = module {
    single<UserRepositoryImpl>() bind UserRepository::class
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral,
   primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
