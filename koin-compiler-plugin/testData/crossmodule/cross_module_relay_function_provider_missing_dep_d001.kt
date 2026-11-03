// RUN_PIPELINE_TILL: BACKEND
// #114: a function provider relayed two hops away keeps its requirements (carried in the relayed
// entry, its own carrier is invisible there): Bar, provided nowhere, is still KOIN-D001.
//
// EXPECTED: KOIN-D001 for Bar.

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

// MODULE: feature(network, types)
// FILE: feature/Feature.kt
package feature

import network.NetworkModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import types.*

@Module(includes = [NetworkModule::class])
@Configuration
@ComponentScan("feature")
class FeatureModule

@Single
class Api(val foo: Foo)

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

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, primaryConstructor,
propertyDeclaration, stringLiteral */
