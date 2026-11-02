// RUN_PIPELINE_TILL: BACKEND
// Guard: when a `fun f(): Module` body can't be followed (it returns its parameter), the
// loaded set is unknown. That must be disclosed (KOIN-W003), never a hard KOIN-D002.
//
// EXPECTED: KOIN-W003, no KOIN-D002 for Publisher.

// MODULE: kernel
// FILE: kernel/Dispatch.kt
package kernel

import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.bind
import org.koin.plugin.module.dsl.single

interface Publisher
class LoggedPublisher : Publisher

val loggedModule = module { single<LoggedPublisher>() bind Publisher::class }

fun passThrough(chosen: Module): Module = chosen

// MODULE: app(kernel)
// FILE: app/DispatchTest.kt
package app

import kernel.Publisher
import kernel.loggedModule
import kernel.passThrough
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Config

fun main() {
    val koin = koinApplication {
        modules(module { single<Config>() }, passThrough(loggedModule))
    }.koin
    koin.get<Publisher>()
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration, lambdaLiteral,
localProperty, outProjection, propertyDeclaration */
