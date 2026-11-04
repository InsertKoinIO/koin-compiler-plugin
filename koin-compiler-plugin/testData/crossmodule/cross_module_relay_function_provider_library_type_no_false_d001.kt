// RUN_PIPELINE_TILL: BACKEND
// GH #114 / #114: `@Single fun provideJson(): Json` reached through a `@Module(includes = …)` relay
// two hops away must stay a FUNCTION provider: Json's own constructor (library internals no graph
// holds) is never its requirements.
//
// EXPECTED: no diagnostics.
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
import org.koin.core.annotation.Single
import serialization.Json

@Module
@Configuration
@ComponentScan("network")
class NetworkModule

@Single
fun provideJson(): Json = Json.build()

// MODULE: feature(network, serialization)
// FILE: feature/Feature.kt
package feature

import network.NetworkModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import serialization.Json

@Module(includes = [NetworkModule::class])
@Configuration
@ComponentScan("feature")
class FeatureModule

@Single
class Api(val json: Json)

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
