// RUN_PIPELINE_TILL: BACKEND
// A definition registered in THIS compilation is this compilation's definition, even when the class
// it provides lives in another Gradle module (no source file here). Its KOIN-D001 must point at the
// DSL call that registers it, and must not borrow the "loaded by:" chain meant for definitions
// declared in another module. One registration per DSL shape, each with its own missing dependency.
//
// Topology:
//   lib : the provided classes; their DepN dependencies are provided nowhere
//   app : App.kt   — val appModule = module { single<T>(), singleOf, create, new, typed create, create(::fn) }
//         Start.kt — koinApplication { modules(appModule) }
//
// EXPECTED: one KOIN-D001 per registration in app.appModule, no "loaded by:" line, each anchored at
// its own registering call in App.kt (not at the modules() call in Start.kt).

// MODULE: lib
// FILE: lib/Lib.kt
package lib

class Clock
class ServiceImpl(val clock: Clock)

class Dep1
class S1(val dep: Dep1)

class Dep2
class S2(val dep: Dep2)

class Dep3
class S3(val dep: Dep3)

interface Api4
class Dep4
class S4(val dep: Dep4) : Api4

class Dep5
class S5
fun buildS5(dep: Dep5): S5 = S5()

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.Api4
import lib.S1
import lib.S2
import lib.S3
import lib.S4
import lib.ServiceImpl
import lib.buildS5
import org.koin.core.module.dsl.new
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

val appModule = module {
    single<ServiceImpl>()
    singleOf(::S1)
    single { create(::S2) }
    single { new(::S3) }
    single<Api4> { create(::S4) }
    single { create(::buildS5) }
}

// FILE: app/Start.kt
package app

import org.koin.dsl.koinApplication

fun start() = koinApplication {
    modules(appModule)
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
