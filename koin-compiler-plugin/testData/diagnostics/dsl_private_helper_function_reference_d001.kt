// RUN_PIPELINE_TILL: BACKEND
// Guard: a private helper also passed as a function reference to a LOADED module can't be
// attributed to its other (unloaded) caller: the missing Store stays KOIN-D001.

import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.ScopeDSL
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped
import org.koin.plugin.module.dsl.single

class Store
class Creator(val store: Store)
class Consumer(val c: Creator)
class Links
class MyScope

private fun Module.beans() {
    single<Creator>()
}

val unloadedModule = module { beans() }

val loadedModule = module(moduleDeclaration = Module::beans)

fun main() {
    startKoin { modules(loadedModule) }
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
