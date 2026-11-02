// RUN_PIPELINE_TILL: BACKEND
// a private helper registering on ANOTHER module (`alertModule.apply { }`, `with(target) { }`)
// puts the definition in that module at runtime, not in its caller. alertModule is loaded, so Creator's
// missing Store must stay KOIN-D001 even though the helper's only caller (wiringModule) is never loaded.

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

private fun Module.wiring() {
    alertModule.apply { single<Creator>() }
}

private fun Module.wiringWith(target: Module) {
    with(target) { single<Consumer>() }
}

val alertModule = module { single<Links>() }

val wiringModule = module {
    wiring()
    wiringWith(alertModule)
}

fun main() {
    startKoin { modules(alertModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
