// RUN_PIPELINE_TILL: BACKEND
// Guard: `loadedModule.beans()` written inside wiringModule registers into loadedModule.
// Not attributed to wiringModule (never loaded): the missing Store stays KOIN-D001.

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

val loadedModule = module { single<Links>() }

val wiringModule = module {
    loadedModule.beans()
}

fun main() {
    startKoin { modules(loadedModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
