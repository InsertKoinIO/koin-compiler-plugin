// RUN_PIPELINE_TILL: BACKEND
// `dataModule(fake)` loads fakeModule or prodModule at runtime. Both count as loaded, so
// Repo's Api (only in fakeModule) and Inspector's FakeClock resolve; the runtime choice is disclosed.
//
// EXPECTED: one KOIN-W011 for dataModule().

// MODULE: kernel
// FILE: kernel/Data.kt
package kernel

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Api
class Repo(val api: Api)
class FakeClock
class Inspector(val clock: FakeClock)

private val fakeModule = module {
    single<Api>()
    single<Repo>()
    single<FakeClock>()
    single<Inspector>()
}
private val prodModule = module { single<Repo>() }

fun dataModule(fake: Boolean) = if (fake) fakeModule else prodModule

// MODULE: app(kernel)
// FILE: app/Main.kt
package app

import kernel.dataModule
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Config

fun main() {
    koinApplication { modules(module { single<Config>() }, dataModule(false)) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, ifExpression, lambdaLiteral, primaryConstructor,
propertyDeclaration */
