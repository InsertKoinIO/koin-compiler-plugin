// RUN_PIPELINE_TILL: BACKEND
// A3 — falsify-first guard for the `@Configuration` auto-discovery relay.
//
// Companion to cross_module_configuration_transitive_ok: SAME transitive topology (`app` depends
// only on `feature`, `feature` depends on `data`, both auto-discovered via bare `@Configuration`),
// but the dependency is genuinely provided by nobody. The relay widens the auto-discovered module
// set, so the risk it introduces is the worst failure class — a real missing dependency going
// silent. This pins that down: reaching DataModule through the relay must make its
// (non-)definitions available WITHOUT making unprovided types resolve.
//
// Topology (identical to the _ok case except MainRepository is never annotated — declared, but no
// @Single):
//   data    : class MainRepository            <-- declared, but NOT @Single
//             @Single class Unrelated
//             @Module @Configuration @ComponentScan("data") class DataModule
//   feature : @Single class MainViewModel(val repository: MainRepository) — needs a type nothing
//             in the closure provides.
//             @Module @Configuration @ComponentScan("feature") class FeatureModule
//   app     : bare @KoinApplication + startKoin<MainApplication>
//
// EXPECTED: KOIN-D001 for data.MainRepository. If this test ever goes silent, the relay has stopped
// validating the modules it made discoverable.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.annotation.Configuration
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

class MainRepository

@Single
class Unrelated

@Module
@Configuration
@ComponentScan("data")
class DataModule

// MODULE: feature(data)
// FILE: feature/Feature.kt
package feature

import org.koin.core.annotation.Configuration
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import data.MainRepository

@Single
class MainViewModel(val repository: MainRepository)

@Module
@Configuration
@ComponentScan("feature")
class FeatureModule

// MODULE: app(feature)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration */
