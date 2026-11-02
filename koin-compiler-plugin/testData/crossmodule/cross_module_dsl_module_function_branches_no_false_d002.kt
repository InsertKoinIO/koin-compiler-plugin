// RUN_PIPELINE_TILL: BACKEND
// `fun f(x) = if (c) a else b` returns one of several module vals. Loading `f(x)` from
// another compilation counts both as loaded, so `get<Publisher>()` (both provide it) is valid. Same
// with `when`. A function returning one other module function is a plain edge (no warning).
//
// EXPECTED: one KOIN-W011 per branching function, no KOIN-D002, no KOIN-W001.

// MODULE: kernel
// FILE: kernel/Dispatch.kt
package kernel

import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.bind
import org.koin.plugin.module.dsl.single

interface Publisher
class LoggedPublisher : Publisher
class TopicPublisher(val ordered: OrderedPublishers) : Publisher
class OrderedPublishers
class Clock

private val orderedPublishersModule = module { single<OrderedPublishers>() }
private val loggedModule = module { single<LoggedPublisher>() bind Publisher::class }
private val topicModule = module {
    includes(orderedPublishersModule)
    single<TopicPublisher>() bind Publisher::class
}

fun dispatchModule(local: Boolean) = if (local) loggedModule else topicModule

fun dispatchModuleByMode(mode: Int): Module = when (mode) {
    0 -> loggedModule
    else -> topicModule
}

private fun clockModule() = module { single<Clock>() }
fun clockAlias(): Module = clockModule()

// MODULE: app(kernel)
// FILE: app/DispatchTest.kt
package app

import kernel.Clock
import kernel.Publisher
import kernel.clockAlias
import kernel.dispatchModule
import kernel.dispatchModuleByMode
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Config

fun main() {
    val koin = koinApplication {
        modules(module { single<Config>() }, dispatchModule(true), dispatchModuleByMode(1), clockAlias())
    }.koin
    koin.get<Publisher>()
    koin.get<Clock>()
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, equalityExpression, functionDeclaration, ifExpression,
integerLiteral, interfaceDeclaration, lambdaLiteral, localProperty, outProjection, primaryConstructor,
propertyDeclaration, whenExpression, whenWithSubject */
