// RUN_PIPELINE_TILL: BACKEND
// Guard: a private helper called from an inline `module { }` at the entry point has no module
// id to take: its definitions stay always-reachable and the missing Store is KOIN-D001.

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

fun main() {
    startKoin { modules(module { beans() }) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
