// RUN_PIPELINE_TILL: BACKEND
// Guard: a recursive private helper is never attributed (no crash): its definitions stay
// always-reachable and the missing Store is KOIN-D001.

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

private fun Module.repeated(times: Int) {
    single<Creator>()
    if (times > 0) repeated(times - 1)
}

val loadedModule = module { repeated(1) }

fun main() {
    startKoin { modules(loadedModule) }
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, comparisonExpression, funWithExtensionReceiver,
functionDeclaration, ifExpression, integerLiteral, lambdaLiteral, primaryConstructor, propertyDeclaration */
