// RUN_PIPELINE_TILL: BACKEND
// inline `module { }` branches and local vals are separate runtime choices too: KOIN-W011.

import org.koin.core.annotation.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.startKoin

class LocalOnly
class RemoteOnly
class DebugTools
class Other
class Common
class X(val c: Common)
class Clock
class Config

fun dataModule(fake: Boolean): Module = if (fake) module { single<DebugTools>() } else module { single<Other>() }

fun localValsModule(fake: Boolean): Module {
    val debug = module { single<LocalOnly>() }
    val prod = module { single<RemoteOnly>() }
    return if (fake) debug else prod
}

fun main() {
    koinApplication { modules(module { single<Config>() }, dataModule(false), localValsModule(true)) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, ifExpression, lambdaLiteral, localProperty,
primaryConstructor, propertyDeclaration */
