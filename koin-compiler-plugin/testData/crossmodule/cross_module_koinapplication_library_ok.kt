// RUN_PIPELINE_TILL: BACKEND
// `koinApplication { }` in a LIBRARY module must not be judged as the application root.
//
// Recognising `org.koin.dsl.koinApplication` as an entry point (8f5fbe2) fixed a real silent gap:
// before it, `koinApplication { }.koin` received NO graph validation at all. But the signal it flips
// is a single boolean, `hasKoinEntryPoint`, and that boolean gates two very different passes:
//
//   Phase 3.1  validate THIS compilation's own DSL graph        <- correct for koinApplication
//   Phase 3.6  validate call-site hints emitted by DEPENDENCY   <- assumes this compilation owns
//              modules, hard-erroring KOIN-D003                    the whole application graph
//
// Phase 3.6's own comment states the assumption: "The app module (which sees all definitions via
// @KoinApplication) validates them here." `startKoin` and `@KoinApplication` do own the app graph —
// they install the global context. `koinApplication { }` deliberately does not; it builds an
// isolated instance, and is the idiom people reach for precisely when they want a partial graph
// (Compose previews, test fixtures, KMP helpers).
//
// Topology:
//   core    : resolves a type nothing provides, with no local DSL definitions, so Phase 3.5 emits a
//             deferred `callsite` hint rather than erroring
//   feature : depends on core, provides its own definition, and builds an isolated Koin instance —
//             a Compose preview or test fixture. It neither owns nor should provide core's type.
//
// EXPECTED: no KOIN-D003 while compiling feature. The hint stays deferred for whatever real
// application root loads both modules; that root is the only place it can be judged. A D003 here is
// a build failure on correct library code.
//
// The distinction Phase 3.6 needs is "does this compilation own an authoritative application graph",
// which is narrower than "is there any entry point" — no fragment semantics required.

// MODULE: core
// FILE: core/Core.kt
package core

import org.koin.core.Koin

// Provided by the real app, never by core or feature.
class AnalyticsTracker

// core deliberately declares NO DSL definitions. Phase 3.5 defers a call site only when the target
// is an external stub OR the module has no local DSL definitions — so this shape is what makes core
// emit a `callsite` hint instead of hard-erroring, which is the precondition for feature's Phase 3.6
// to have anything to read. (That heuristic is itself questionable — a library with any local
// definition hard-errors instead of deferring — but it is pre-existing and tracked separately.)
fun track(koin: Koin): AnalyticsTracker = koin.get()

// MODULE: feature(core)
// FILE: feature/Feature.kt
package feature

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class FeatureThing

val featureModule = module {
    single<FeatureThing>()
}

// An isolated Koin instance — the Compose-preview / test-fixture shape.
fun preview() {
    koinApplication {
        modules(featureModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, propertyDeclaration */
