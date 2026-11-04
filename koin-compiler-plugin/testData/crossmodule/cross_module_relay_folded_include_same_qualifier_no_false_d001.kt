// RUN_PIPELINE_TILL: BACKEND
// #114/5303: both @Named("io") providers (Dispatcher in P, Scope in P's include Q) reach the app only
// through feature's relay of P.
//
// EXPECTED: no diagnostics.

// MODULE: types
// FILE: types/Types.kt
package types

interface Dispatcher
interface Scope
class DispatcherImpl : Dispatcher
class ScopeImpl : Scope

// MODULE: q(types)
// FILE: q/Q.kt
package q

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import types.*

@Module
@ComponentScan("q")
class QModule

@Single
@Named("io")
fun ioScope(): Scope = ScopeImpl()

// MODULE: p(q, types)
// FILE: p/P.kt
package p

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import q.QModule
import types.*

@Module(includes = [QModule::class])
@Configuration
@ComponentScan("p")
class PModule

@Single
@Named("io")
fun ioDispatcher(): Dispatcher = DispatcherImpl()

// MODULE: feature(p, types)
// FILE: feature/Feature.kt
package feature

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import p.PModule
import types.*

@Module(includes = [PModule::class])
@Configuration
@ComponentScan("feature")
class FeatureModule

@Single
class Repo(@Named("io") val d: Dispatcher, @Named("io") val s: Scope)

// MODULE: app(feature, types)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
class App

fun main() {
    startKoin<App>()
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, interfaceDeclaration,
primaryConstructor, propertyDeclaration, stringLiteral */
