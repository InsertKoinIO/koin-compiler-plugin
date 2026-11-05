// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// KOIN-W003 must point at the argument compile safety could not read, and name it.
// `extras` is a property whose value is chosen at runtime; `appModule` next to it resolves fine.
//
// EXPECTED: one KOIN-W003 anchored at the `modules(extras)` argument, naming property 'extras'.
// No D001/D002/W001: the graph is correct at runtime.
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
        modules(
            extras
        )
    }.koin
    val repo = koin.get<Repository>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, ifExpression, lambdaLiteral, localProperty,
primaryConstructor, propertyDeclaration */
