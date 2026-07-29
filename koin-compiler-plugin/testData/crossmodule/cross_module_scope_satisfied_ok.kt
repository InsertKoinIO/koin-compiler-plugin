// RUN_PIPELINE_TILL: BACKEND
// Cross-module TYPED-scope resolution, post-A2-removal (1.1.0). SessionConsumer needs SessionData
// from within the SAME `@Scope(SessionScope::class)` — provided by a peer module
// (`sessionProvider`) that `consumer` has no Gradle dependency on at all. Only unified at app's
// entry point. Proves A3 matches the scope key (not just the type) correctly across module
// boundaries.
//
// NOTE: BindingRegistry.findProvider only checks the TYPED scope (scopeClass, via
// @Scope(X::class)) for visibility — a NAMED scope (@Scope(name = "...")) has no scopeClass, so
// it is treated as root-scope-visible-everywhere regardless of name (a pre-existing limitation,
// found while writing this test, not something this test changes). Typed scope is therefore the
// only shape that actually exercises scope-based visibility restriction at compile time.
//
// EXPECTED: empty .errors.txt — SessionData resolves within the shared SessionScope.

// MODULE: contracts
// FILE: contracts/Contracts.kt
package contracts

interface SessionData
class SessionScope

// MODULE: sessionProvider(contracts)
// FILE: sessionProvider/SessionDataImpl.kt
package sessionprovider

import contracts.SessionData
import contracts.SessionScope
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

@Scope(SessionScope::class)
@Scoped
class SessionDataImpl : SessionData

// FILE: sessionProvider/SessionProviderModule.kt
package sessionprovider

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

@Module
@ComponentScan("sessionprovider")
class SessionProviderModule

// MODULE: consumer(contracts)
// FILE: consumer/SessionConsumer.kt
package consumer

import contracts.SessionData
import contracts.SessionScope
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

// Needs SessionData from within the SAME typed scope — this module has no Gradle dependency on
// whoever provides it inside that scope.
@Scope(SessionScope::class)
@Scoped
class SessionConsumer(val data: SessionData)

// FILE: consumer/ConsumerModule.kt
package consumer

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

@Module
@ComponentScan("consumer")
class ConsumerModule

// MODULE: app(consumer, sessionProvider)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin
import consumer.ConsumerModule
import sessionprovider.SessionProviderModule

@KoinApplication(modules = [ConsumerModule::class, SessionProviderModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, interfaceDeclaration,
lambdaLiteral, objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
