// RUN_PIPELINE_TILL: BACKEND
// `KoinApplication.withConfiguration<T>()` chained onto a KNOWN isolated `koinApplication { }`
// instance must NOT be judged as owning the authoritative application graph — the exact shape this
// project's own KDoc documents (KoinStartTransformer.kt's class doc:
// `koinApplication { }.withConfiguration<MyApp>()`), and the same idiom
// cross_module_koinapplication_library_ok.kt already covers for plain `koinApplication { }`.
//
// A prior fix made `EntryKind.WITH_CONFIGURATION` unconditionally authoritative (to cover the
// canonical `startKoin { }.withConfiguration<T>()` shape). That was too broad: it also flipped
// `koinApplication { }.withConfiguration<T>()` — a deliberately isolated fragment (Compose preview,
// test fixture) just configured via a different function — to authoritative, reintroducing the
// exact false-positive class the koinApplication{} regression test exists to prevent. Fixed by only
// treating `withConfiguration<T>()` as authoritative when it is NOT chained directly onto a known
// isolated-instance-building call (`koinApplication { }` / typed `koinApplication<T>()`).
//
// Topology mirrors cross_module_koinapplication_library_ok.kt exactly, but feature builds its
// isolated instance via withConfiguration<PreviewApp>() instead of `modules(featureModule)`:
//   core    : resolves a type nothing provides -> deferred `callsite` hint (Phase 3.5)
//   feature : depends on core, builds an ISOLATED instance via
//             koinApplication { }.withConfiguration<PreviewApp>() — a Compose preview / test
//             fixture. It neither owns nor should provide core's type.
//
// EXPECTED: no KOIN-D003 while compiling feature. The hint stays deferred for whatever real
// application root loads both modules.

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

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.withConfiguration
import org.koin.core.annotation.KoinApplication as KoinApp

@Single
class FeatureThing

@Module
@ComponentScan("feature")
class FeatureModule

@KoinApp(modules = [FeatureModule::class])
object PreviewApp

// An isolated Koin instance, just configured via withConfiguration<T>() instead of modules(...) —
// still the Compose-preview / test-fixture shape.
fun preview() {
    koinApplication { }.withConfiguration<PreviewApp>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, objectDeclaration */
