// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression: singleOf(::fn) { named("x") } must still register the definition under that
// qualifier even though its own REQUIREMENTS are no longer derived (KOIN-W007 — see
// CONSTRUCTOR_SHORTHAND_DEF_TYPES's kdoc). Dropping the qualifier too (as an earlier version of
// that change accidentally did, by deleting collectNamedQualifier outright) collapses two
// differently-qualified singleOf registrations of the same type into one unqualified provider —
// a false KOIN-D001 for Consumer below, on code that resolves correctly at runtime.
package testpkg

import org.koin.core.annotation.Named
import org.koin.core.module.dsl.named
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository(val label: String)
class Consumer(@Named("a") val repo: Repository)

fun makeA() = Repository("a")
fun makeB() = Repository("b")

val appModule = module {
    singleOf(::makeA) { named("a") }
    singleOf(::makeB) { named("b") }
    single<Consumer>()
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor,
   propertyDeclaration, topLevelPropertyDeclaration */
