// RUN_PIPELINE_TILL: BACKEND
// a @Configuration function provider whose requirement type is off the relaying modules'
// classpath, three hops from the entry point. The requirement must survive re-encoding by each relay.
//
// EXPECTED: KOIN-D001 for types.Bar, required by the provider of types.Foo.

// MODULE: types
// FILE: types/Types.kt
package types

class Bar
class Foo(val bar: Bar)

// MODULE: network(types)
// FILE: network/Network.kt
package network

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import types.*

@Module
@Configuration
@ComponentScan("network")
class NetworkModule

@Single
fun provideFoo(bar: Bar): Foo = Foo(bar)

// MODULE: feature(network)
// FILE: feature/Feature.kt
package feature

class FeatureThing

// MODULE: mid(feature)
// FILE: mid/Mid.kt
package mid

class MidThing

// MODULE: app(mid)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
class App

fun main() {
    startKoin<App>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
