// RUN_PIPELINE_TILL: BACKEND
// A3 — cross-module ANNOTATION transitive `@Module(includes = [...])` reachability (issue #82).
//
// `@Module(includes=[X::class])` IS ABI (unlike DSL's `includes()`, which lives in a lambda body),
// so a direct reader can normally resolve `X::class` off the classpath. That resolvability breaks
// down one hop further: reading X's OWN `includes=[...]` requires X's included classes to ALSO be
// on the reader's classpath — and Gradle `implementation` (non-transitive) scoping deliberately
// hides anything beyond a direct dependency. `getModuleIncludes()` silently dropped what it could
// not resolve, with no diagnostic and no hint-based fallback (unlike ComponentScan discovery,
// which is fully hint-based). Reported by @kfaraj against 1.1.0:
// https://github.com/InsertKoinIO/koin-compiler-plugin/issues/82#issuecomment-5233645510
//
// Topology — mirrors the real-world report exactly. `app` depends ONLY on `feature`, NOT on `data`
// (the shape existing crossmodule tests for DSL `includes()` never exercised — they all declared
// `app(lib, base)`, giving `app` direct classpath visibility of the deepest module):
//   data    : @Single class MainRepository
//             @Module @ComponentScan("data") class DataModule
//   feature : @Single class MainViewModel(val repository: MainRepository)
//             @Module(includes = [DataModule::class]) @ComponentScan("feature") class FeatureModule
//             — `data` is NOT on `app`'s classpath, so `app` can only learn about DataModule (and
//               MainRepository) via FeatureModule's re-published includes hint.
//   app     : @Module(includes = [FeatureModule::class]) class AppModule
//             @KoinApplication(modules = [AppModule::class]) + startKoin<MainApplication>
//
// EXPECTED (after the includes-edge hint carrier): SILENT. AppModule -> FeatureModule -> DataModule
// is a complete graph — Koin resolves this fine at runtime via `includes()`'s own transitivity.
// Before the carrier: false KOIN-D001 for data.MainRepository, in module MainApplication (startKoin).

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Single

@Single
class MainRepository

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
