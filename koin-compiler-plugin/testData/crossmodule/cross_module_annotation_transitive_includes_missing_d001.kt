// RUN_PIPELINE_TILL: BACKEND
// A3 — falsify-first guard for the ANNOTATION includes-edge carrier.
//
// Companion to cross_module_annotation_transitive_includes_ok: SAME transitive topology (`app`
// depends only on `feature`, `feature` depends on `data`), but the dependency is genuinely provided
// by nobody. The carrier widens the reachable module set, so the risk it introduces is the worst
// failure class — a real missing dependency going silent. This test pins that down: reaching
// DataModule through FeatureModule's `includes=[...]` must make its (non-)definitions available
// WITHOUT making unprovided types resolve.
//
// Topology (identical to the _ok case except MainRepository is never annotated — declared, but no
// @Single):
//   data    : class MainRepository            <-- declared, but NOT @Single
//             @Single class Unrelated
//             @Module @ComponentScan("data") class DataModule
//   feature : @Single class MainViewModel(val repository: MainRepository) — needs a type nothing in
//             the closure provides.
//             @Module(includes = [DataModule::class]) @ComponentScan("feature") class FeatureModule
//   app     : @Module(includes = [FeatureModule::class]) class AppModule
//             @KoinApplication(modules = [AppModule::class]) + startKoin<MainApplication>
//
// EXPECTED: KOIN-D001 for data.MainRepository. If this test ever goes silent, the carrier has
// stopped validating the modules it made reachable.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Single

class MainRepository

@Single
class Unrelated

@Module
@ComponentScan("data")
class DataModule

// MODULE: feature(data)
// FILE: feature/Feature.kt
package feature

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Single
import data.DataModule
import data.MainRepository

@Single
class MainViewModel(val repository: MainRepository)

@Module(includes = [DataModule::class])
@ComponentScan("feature")
class FeatureModule

// MODULE: app(feature)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.plugin.module.dsl.startKoin
import feature.FeatureModule

@Module(includes = [FeatureModule::class])
class AppModule

@KoinApplication(modules = [AppModule::class])
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
