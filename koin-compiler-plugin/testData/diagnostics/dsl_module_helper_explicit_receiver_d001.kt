// RUN_PIPELINE_TILL: BACKEND
// Guard (public or member helper, not attributed: unchanged behavior). Shape below.
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Store
class Creator(val store: Store)
class Consumer(val c: Creator)

fun Module.beans() {
    single<Creator>()
}

val loadedModule = module { single<Consumer>() }

val wiringModule: Module = module {
    loadedModule.beans()
}

fun main() {
    startKoin { modules(loadedModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
