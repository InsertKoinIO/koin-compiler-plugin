// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// The discriminating case for fail-open: an entry point that is PARTLY resolvable.
//
// `entry_modules_list_variable_ok` has a single unresolvable `modules(...)`, so nothing at all is
// recorded and the reachability walk's own empty-input guard already produces the right answer —
// that test passes with or without the fail-open flag, so it does not prove the flag works.
//
// Here one call resolves and one does not:
//
//     modules(appModule)     // resolvable
//     modules(extras)        // a runtime conditional — not statically resolvable
//
// (`listOf(...)` is now resolved precisely, see dsl_module_listof_*, so a conditional is used here
// to keep this genuinely unresolvable.)
//
// Without fail-open, `appModule` is treated as the WHOLE loaded set and everything reachable only
// through `extras` is classified unreachable: KOIN-D001 for Service's constructor dependency,
// KOIN-D002 for the call site, KOIN-W001 for coreModule — three false positives on a valid graph.
//
// EXPECTED: no D001/D002/W001, plus KOIN-W003 disclosing that the graph could not be verified.
// The disclosure is the point. Silence would let a green build imply a guarantee it never made,
// which is what KOIN-W003's own contract forbids — and the `modules(vararg KClass)` path already
// discloses this exact class of unknowability.
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

fun isDynamic(): Boolean = true
val extras = if (isDynamic()) listOf(coreModule) else listOf(coreModule)

fun useIt() {
    val koin = koinApplication {
        modules(appModule)
        modules(extras)
    }.koin
    val repo = koin.get<Repository>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty,
   primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
