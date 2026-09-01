// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// singleOf(::Ctor) is Koin's own constructor-shorthand DSL (org.koin.core.module.dsl). Resolving
// the `::Ctor` argument needs no per-arity parsing — it's one IrFunctionReference regardless of
// which of Koin's ~20 reified-arity overloads called it, the exact same shape create(::T) already
// resolves — so its requirements ARE derived via the same shared requirementsFor helper (see
// KoinDSLTransformer.collectConstructorShorthandDef). NeedsMissing's constructor requires
// Missing, which has no provider: this must now be a hard KOIN-D001, not silently registered
// provider-only (that was the intentional-but-since-reverted precision downgrade this test
// replaces — formerly dsl_singleof_unsafe_w007, which asserted only KOIN-W007 fired here).
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
