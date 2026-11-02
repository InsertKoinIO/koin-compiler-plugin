// RUN_PIPELINE_TILL: BACKEND
// Guard (public or member helper, not attributed: unchanged behavior). Shape below.
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.ScopeDSL
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped
import org.koin.plugin.module.dsl.single

class MyScope
class Store
class Scoped1(val store: Store)
class Other

fun ScopeDSL.scopedBeans() {
    scoped<Scoped1>()
}

val rootModule = module {
    single<Other>()
    scope<MyScope> { scopedBeans() }
}

val unloadedModule = module {
    scope<MyScope> { scopedBeans() }
}

fun main() {
    startKoin { modules(rootModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
