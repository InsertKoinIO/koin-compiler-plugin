// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// `modules(someListOfModules)` must not make the real modules look unloaded.
//
// The entry-point walk records a module id for arguments to `modules(...)`, but it does not check
// that the argument is actually of type `Module`. Koin also ships `modules(modules: List<Module>)`,
// and collecting modules into a list first is a very common app shape:
//
//     val allModules = listOf(appModule, coreModule)
//     startKoin { modules(allModules) }
//
// If the LIST property is recorded as the loaded module, the reachability walk resolves only
// `allModules` — and `appModule` / `coreModule`, which actually hold every definition, are
// classified unreachable. Historically that produced KOIN-W001 warnings (already wrong, but
// harmless). Since call-site validation started subtracting unreachable-only types, the same
// misreading escalates: every type those modules provide is withheld, and a legitimate
// `get<Repository>()` reports KOIN-D002 — a build failure on correct code.
//
// The graph below is complete and correct: both modules are loaded via the list, and Repository is
// provided by coreModule.
//
// EXPECTED: completely silent. Any KOIN-D002 or KOIN-W001 here is a false positive.
//
// `listOf(...)` itself is now resolved precisely (see dsl_module_listof_*) rather than falling back
// to fail-open — a stronger guarantee than before (full verification, not just "no false positive").
// This test still pins the historical misread bug; entry_modules_partial_resolution_w003 covers a
// genuinely unresolvable case instead.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)

val coreModule = module {
    single<Repository>()
}

val appModule = module {
    single<Service>()
}

val allModules = listOf(appModule, coreModule)

fun useIt() {
    val koin = koinApplication { modules(allModules) }.koin
    val repo = koin.get<Repository>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty,
   primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
