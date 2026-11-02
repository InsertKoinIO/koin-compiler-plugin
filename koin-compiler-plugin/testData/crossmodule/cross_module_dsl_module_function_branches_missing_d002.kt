// RUN_PIPELINE_TILL: BACKEND
// Guard: counting every branch as loaded must not hide a type NO branch provides: Audit is
// still a hard KOIN-D002. The runtime choice itself is disclosed (KOIN-W011).
//
// EXPECTED: KOIN-W011 for dispatchModule(), KOIN-D002 for Audit.

// MODULE: kernel
// FILE: kernel/Dispatch.kt
package kernel

import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface Publisher
class LoggedPublisher : Publisher
class TopicPublisher : Publisher
class Audit

private val loggedModule = module { single<LoggedPublisher>() bind Publisher::class }
private val topicModule = module { single<TopicPublisher>() bind Publisher::class }

fun dispatchModule(local: Boolean) = if (local) loggedModule else topicModule

// MODULE: app(kernel)
// FILE: app/DispatchTest.kt
package app

import kernel.Audit
import kernel.Publisher
import kernel.dispatchModule
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Config

fun main() {
    val koin = koinApplication {
        modules(module { single<Config>() }, dispatchModule(false))
    }.koin
    koin.get<Publisher>()
    koin.get<Audit>()
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, ifExpression, interfaceDeclaration,
lambdaLiteral, localProperty, outProjection, propertyDeclaration */
