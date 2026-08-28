// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// singleOf(::Ctor) is Koin's own constructor-shorthand DSL (org.koin.core.module.dsl) — a real
// Koin function with ~20 reified-arity overloads. Parsing every arity to recover the constructor
// shape isn't worth the maintenance cost, so its requirements are no longer derived: this
// definition is registered provider-only and its own dependencies are disclosed via KOIN-W007,
// NOT hard-validated. NeedsMissing's constructor requires Missing, which has no provider — under
// the old constructor-derived-requirements design this fired KOIN-D001; now it must NOT, only
// KOIN-W007. Falsify-first companion, inverted: dsl_singleof_precision_d001 used to assert D001
// fired here — this test proves the intentional precision downgrade instead.
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
