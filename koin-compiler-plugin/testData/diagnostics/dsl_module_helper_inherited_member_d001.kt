// RUN_PIPELINE_TILL: BACKEND
// Guard (public or member helper, not attributed: unchanged behavior). Shape below.
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Store
class Creator(val store: Store)
class Consumer(val c: Creator)

abstract class BaseModules {
    fun Module.commonBeans() {
        single<Creator>()
    }
}

object AppModules : BaseModules() {
    val appModule = module {
        commonBeans()
        single<Consumer>()
    }
}

fun main() {
    startKoin { modules(AppModules.appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral, objectDeclaration,
primaryConstructor, propertyDeclaration */
