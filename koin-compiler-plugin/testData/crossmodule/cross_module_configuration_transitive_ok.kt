// RUN_PIPELINE_TILL: BACKEND
// A3 — cross-module `@Configuration` auto-discovery reachability 2+ `implementation` hops away.
//
// `configuration_<label>` hints ARE emitted unconditionally at FIR time for every
// `@Module` + `@Configuration`-annotated class, so a DIRECT reader always finds them. But
// discovery (`KoinStartTransformer.discoverModulesFromHints`) only runs `context.referenceFunctions`
// against the QUERYING compilation's OWN classpath — a `@Configuration` module 2+ `implementation`
// hops from the real entry point is simply never on that classpath, so no amount of querying finds
// it. Same root cause as the `includes()` gap (issue #82), different mechanism: auto-discovery by
// label, not an explicit edge.
//
// Topology — `app` depends ONLY on `feature`, NOT on `data`:
//   data    : @Single class MainRepository
//             @Module @Configuration @ComponentScan("data") class DataModule (default label)
//   feature : @Single class MainViewModel(val repository: MainRepository)
//             @Module @Configuration @ComponentScan("feature") class FeatureModule (default label)
//             — `data` is NOT on `app`'s classpath, so `app` can only learn about DataModule via
//               FeatureModule's re-published configuration_default hint.
//   app     : bare @KoinApplication (no explicit modules=[...] — pure auto-discovery by label) +
//             startKoin<MainApplication>
//
// EXPECTED (after the relay): SILENT. Both FeatureModule and DataModule auto-discover into the
// graph. Before the relay: false KOIN-D001 for data.MainRepository, in module MainApplication
// (startKoin) — DataModule's default-label hint is invisible to `app`, so it never gets discovered.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.annotation.Configuration
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Single
class MainRepository

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
