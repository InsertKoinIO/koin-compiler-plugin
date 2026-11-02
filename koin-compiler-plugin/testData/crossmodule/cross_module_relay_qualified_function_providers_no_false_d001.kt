// RUN_PIPELINE_TILL: BACKEND
// #114: same relay as the #114 test, with QUALIFIED function providers: each must be relayed
// under its own per-qualifier entry (plus roster) so a `@Named` requirement two hops away resolves,
// and neither provider is read as Json's constructor.
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
class Api(@Named("strict") val json: Json, @Named("lenient") val lenient: Json)

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
