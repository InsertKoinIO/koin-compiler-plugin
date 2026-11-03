// RUN_PIPELINE_TILL: BACKEND
// #114/5303: mid scans a package holding its own @Named("io") Dispatcher provider and a dependency's
// @Named("io") Scope provider; it re-publishes both as function providers. The app sees mid only.
//
// EXPECTED: no diagnostics.

// MODULE: types
// FILE: types/Types.kt
package types

interface Dispatcher
interface Scope
class DispatcherImpl : Dispatcher
class ScopeImpl : Scope

// MODULE: common(types)
// FILE: shared/Common.kt
package shared

import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import types.*

@Single
@Named("io")
fun ioScope(): Scope = ScopeImpl()

// MODULE: mid(common, types)
// FILE: shared/Mid.kt
package shared

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import types.*

@Module
@Configuration
@ComponentScan("shared")
class MidModule

@Single
@Named("io")
fun ioDispatcher(): Dispatcher = DispatcherImpl()

@Single
class Repo(@Named("io") val d: Dispatcher, @Named("io") val s: Scope)

// MODULE: app(mid, types)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
class App

fun main() {
    startKoin<App>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, primaryConstructor,
propertyDeclaration, stringLiteral */
