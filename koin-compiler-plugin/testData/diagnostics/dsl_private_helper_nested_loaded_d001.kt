// RUN_PIPELINE_TILL: BACKEND
// nested private helpers called from a loaded module belong to it; a missing dependency is
// KOIN-D001 attributed to that module.

import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Store
class Creator(val store: Store)
class Consumer(val c: Creator)

private fun Module.outerBeans() {
    innerBeans()
}

private fun Module.innerBeans() {
    single<Creator>()
}

val rootModule = module {
    outerBeans()
    single<Consumer>()
}

fun main() {
    startKoin { modules(rootModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
