// RUN_PIPELINE_TILL: BACKEND
// an exhaustive enum `when` (the compiler adds a throwing `else`), an `else -> error(...)`
// branch and a custom getter: the throwing branches load nothing, the rest count as loaded. No
// KOIN-W003, no KOIN-D002; one KOIN-W011 per runtime choice.

import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface Publisher
class LoggedPublisher : Publisher
class TopicPublisher : Publisher
class Config

enum class Env { LOCAL, CLOUD }

val loggedModule = module { single<LoggedPublisher>() bind Publisher::class }
val topicModule = module { single<TopicPublisher>() bind Publisher::class }

fun byEnv(env: Env): Module = when (env) {
    Env.LOCAL -> loggedModule
    Env.CLOUD -> topicModule
}

fun byName(name: String): Module = when (name) {
    "local" -> loggedModule
    "cloud" -> topicModule
    else -> error("unknown $name")
}

var isLocal = true
val dispatchGetter: Module get() = if (isLocal) loggedModule else topicModule

fun main() {
    val koin = koinApplication {
        modules(module { single<Config>() }, byEnv(Env.LOCAL), byName("cloud"), dispatchGetter)
    }.koin
    koin.get<Publisher>()
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, enumDeclaration, enumEntry, equalityExpression,
functionDeclaration, getter, ifExpression, interfaceDeclaration, lambdaLiteral, localProperty, outProjection,
propertyDeclaration, smartcast, stringLiteral, whenExpression, whenWithSubject */
