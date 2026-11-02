// RUN_PIPELINE_TILL: BACKEND
// includes() inside a private helper belongs to the module calling it, so otherModule is
// loaded through rootModule: no KOIN-D001 for Consumer's Creator, no KOIN-W001.
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Creator
class Consumer(val c: Creator)

val otherModule = module { single<Creator>() }

private fun Module.wiring() {
    includes(otherModule)
}

val rootModule = module {
    wiring()
    single<Consumer>()
}

fun main() {
    startKoin { modules(rootModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
