// RUN_PIPELINE_TILL: BACKEND
// `overrides ?: module { }` returns the caller's argument when given: the loaded set is
// unknown. Disclose it (KOIN-W003), never a hard KOIN-D002 for what the override provides.

import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Default
class Override
class Config

val overridesModule = module { single<Override>() }

fun appModule(overrides: Module? = null): Module = overrides ?: module { single<Default>() }

fun main() {
    val koin = koinApplication { modules(module { single<Config>() }, appModule(overridesModule)) }.koin
    koin.get<Override>()
}

/* GENERATED_FIR_TAGS: classDeclaration, elvisExpression, functionDeclaration, lambdaLiteral, localProperty,
nullableType, propertyDeclaration */
