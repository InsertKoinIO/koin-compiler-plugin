// RUN_PIPELINE_TILL: BACKEND
// `KoinApplication.withConfiguration<T>()` DOES own the authoritative application graph — unlike
// `koinApplication { }` (the sibling test cross_module_koinapplication_library_ok.kt covers that
// side), `withConfiguration<T>()` mutates an ALREADY-REAL `KoinApplication` instance via its
// extension receiver (here: threaded in as a parameter — the shape a shared bootstrap helper uses,
// with no `startKoin { }` call anywhere in THIS compilation to separately signal ownership) rather
// than building a fresh isolated one.
//
// Before this fix, `ownsAuthoritativeGraph` only counted `EntryKind.START_KOIN`, so a compilation
// whose only entry point is `withConfiguration<T>()` was wrongly treated as NOT owning the app
// graph — Phase 3.6 (KOIN-D003 for deferred cross-module call sites) silently skipped validating
// call sites it should have judged, a silent gap on exactly the kind of app this idiom is for.
//
// Topology mirrors cross_module_koinapplication_library_ok.kt, but feature owns the app here (via
// withConfiguration<MyApp>()) instead of building an isolated instance:
//   core    : resolves a type nothing provides -> deferred `callsite` hint (Phase 3.5)
//   feature : depends on core, is the REAL application (via withConfiguration<MyApp>()), and never
//             provides core's type either
//
// EXPECTED: KOIN-D003 while compiling feature — the call site is genuinely unresolvable anywhere,
// and feature now correctly owns the authoritative graph that must judge it.

// MODULE: core
// FILE: core/Core.kt
package core

import org.koin.core.Koin

// Provided by the real app, never by core or feature.
class AnalyticsTracker

fun track(koin: Koin): AnalyticsTracker = koin.get()

// MODULE: feature(core)
// FILE: feature/Feature.kt
package feature

import org.koin.core.KoinApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.withConfiguration
import org.koin.core.annotation.KoinApplication as KoinApp

@Single
class FeatureThing

@Module
@ComponentScan("feature")
class FeatureModule

@KoinApp(modules = [FeatureModule::class])
object MyApp

// A shared bootstrap helper — the KoinApplication instance arrives as a parameter, not a literal
// `startKoin { }` call visible in this compilation.
fun bootstrap(app: KoinApplication) {
    app.withConfiguration<MyApp>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, objectDeclaration, propertyDeclaration */
