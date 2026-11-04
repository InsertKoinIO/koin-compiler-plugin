// RUN_PIPELINE_TILL: BACKEND
// `dispatchModule(debug)` loads debugModule or prodModule at runtime. Both count as loaded
// (no false KOIN-D002 for Publisher or DebugTools), and the runtime choice is disclosed once: DebugTools
// exists only in debugModule, which compile safety can't tell apart from the prod path.
//
// EXPECTED: one KOIN-W011 for dispatchModule().

// MODULE: kernel
// FILE: kernel/Dispatch.kt
package kernel

import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface Publisher
class LoggedPublisher : Publisher
class TopicPublisher : Publisher
class DebugTools

private val debugModule = module {
    single<LoggedPublisher>() bind Publisher::class
    single<DebugTools>()
}
private val prodModule = module { single<TopicPublisher>() bind Publisher::class }

fun dispatchModule(debug: Boolean) = if (debug) debugModule else prodModule

// MODULE: app(kernel)
// FILE: app/Main.kt
package app

import kernel.DebugTools
import kernel.Publisher
import kernel.dispatchModule
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Config

fun main() {
    val koin = koinApplication { modules(module { single<Config>() }, dispatchModule(false)) }.koin
    koin.get<Publisher>()
    koin.get<DebugTools>()
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, ifExpression, interfaceDeclaration,
lambdaLiteral, localProperty, outProjection, propertyDeclaration */
