// RUN_PIPELINE_TILL: BACKEND
// a private ScopeDSL helper inside `scope<T> { }` belongs to the enclosing module: loaded
// rootModule → its missing Store is KOIN-D001 in rootModule; unloadedModule's copy is unreachable (W001).

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

private fun ScopeDSL.scopedBeans() {
    scoped<Creator>()
}

val rootModule = module {
    single<Links>()
    scope<MyScope> { scopedBeans() }
}

private fun ScopeDSL.otherScopedBeans() {
    scoped<Consumer>()
}

val unloadedModule = module {
    scope<MyScope> { otherScopedBeans() }
}

fun main() {
    startKoin { modules(rootModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
