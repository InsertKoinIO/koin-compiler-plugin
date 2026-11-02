// RUN_PIPELINE_TILL: BACKEND
// Guard: a private helper called from two modules can't be attributed to one; its defs stay
// always-reachable (unchanged behavior), so the loaded caller's missing Store is still KOIN-D001.

import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Store
class Creator(val store: Store)
class Consumer(val c: Creator)

private fun Module.beans() {
    single<Creator>()
}

val loadedModule = module {
    beans()
    single<Consumer>()
}

val unloadedModule = module { beans() }

fun main() {
    startKoin { modules(loadedModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
