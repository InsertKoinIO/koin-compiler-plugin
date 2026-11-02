// RUN_PIPELINE_TILL: BACKEND
// Single compilation: `dispatchModule()` loads debugModule or prodModule at runtime. Both
// count as loaded (no false KOIN-D002); the runtime choice is disclosed once.

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class DebugTools
class Shared
class Config

val debugModule = module {
    single<Shared>()
    single<DebugTools>()
}
val prodModule = module { single<Shared>() }

fun dispatchModule(debug: Boolean) = if (debug) debugModule else prodModule

fun main() {
    val koin = koinApplication { modules(module { single<Config>() }, dispatchModule(false)) }.koin
    koin.get<Shared>()
    koin.get<DebugTools>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, ifExpression, lambdaLiteral, localProperty,
propertyDeclaration */
