// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Call-site (Phase 3.5) analogue of entry_dsl_dynamic_modules_missing_softened.kt: same
// dynamically-computed module set, but the genuine miss is exercised via a koin.get<T>() call site
// (KOIN-D002) instead of a constructor parameter (KOIN-D001) — pins the validatePendingCallSites
// half of the completeness gate, not just BindingRegistry.validateModule's.
//
// EXPECTED: only KOIN-W003. No KOIN-D002 for get<Missing>().
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Other
class Missing

val appModule = module {
    single<Other>()
}

val extras = listOf(appModule)

fun useIt() {
    val koin = koinApplication {
        modules(appModule)
        modules(extras)
    }.koin
    val missing = koin.get<Missing>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty,
   propertyDeclaration, topLevelPropertyDeclaration */
