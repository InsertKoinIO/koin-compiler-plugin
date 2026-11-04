// RUN_PIPELINE_TILL: BACKEND
// #114 guard: relaying qualified function providers must not over-provide. Api needs a
// @Named("absent") Json that no relayed provider has: still a hard KOIN-D001.
//
// EXPECTED: KOIN-D001 for @Named("absent") Json only.
// MODULE: serialization
// FILE: serialization/Json.kt
package serialization

class JsonConfiguration
class SerializersModule
class Json internal constructor(val configuration: JsonConfiguration, val serializersModule: SerializersModule) {
    companion object {
        fun build(): Json = Json(JsonConfiguration(), SerializersModule())
    }
}

// MODULE: network(serialization)
// FILE: network/Network.kt
package network

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import serialization.Json

@Module
@Configuration
@ComponentScan("network")
class NetworkModule

@Single
@Named("strict")
fun provideStrictJson(): Json = Json.build()

@Single
@Named("lenient")
fun provideLenientJson(): Json = Json.build()

// MODULE: feature(network, serialization)
// FILE: feature/Feature.kt
package feature

import network.NetworkModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import serialization.Json

@Module(includes = [NetworkModule::class])
@Configuration
@ComponentScan("feature")
class FeatureModule

@Single
class Api(@Named("strict") val json: Json, @Named("absent") val missing: Json)

// MODULE: app(feature, serialization)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
class App

fun main() {
    startKoin<App>()
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, companionObject, functionDeclaration,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
