// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression: singleOf(::fn) { named("x") } must register the definition under that qualifier —
// dropping it (as an earlier version of a since-reverted change accidentally did, by deleting
// collectNamedQualifier outright) collapses two differently-qualified singleOf registrations of
// the same type into one unqualified provider — a false KOIN-D001 for Consumer below, on code
// that resolves correctly at runtime. (Historical note: singleOf's own requirements were briefly
// not derived at all when this test was written — since reinstated, see
// KoinDSLTransformer.collectConstructorShorthandDef — so makeA/makeB's own zero-arg signatures are
// now genuinely validated too, not just their qualifiers.)
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
