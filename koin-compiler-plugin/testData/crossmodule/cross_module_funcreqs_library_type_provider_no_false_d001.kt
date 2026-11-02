// RUN_PIPELINE_TILL: BACKEND
// GH #114 / #96: `@Single fun provideJson(): Json` provides a LIBRARY type whose own constructor
// takes things no Koin graph holds. The provider has no parameters: nothing to validate, and the
// library class's constructor must never be read as the provider's requirements.
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

class Api(val json: Json)

@Single
fun provideApi(json: Json): Api = Api(json)

// MODULE: app(network, serialization)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
class App

fun main() {
    startKoin<App>()
}

/* GENERATED_FIR_TAGS: classDeclaration, companionObject, functionDeclaration, objectDeclaration, primaryConstructor,
propertyDeclaration, stringLiteral */
