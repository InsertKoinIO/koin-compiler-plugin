// RUN_PIPELINE_TILL: BACKEND
// #58: a `Koin` built inside a helper from another module (a shared test fixture) is
// not the graph of this compilation's own `koinApplication { }`. A call site on it must not be
// judged against that unrelated graph.
//
// Topology:
//   testing : the helper. Builds its own Koin from `infraModule`, which provides Clock.
//   spec    : one test builds an isolated `koinApplication { }` (an entry point + a local module);
//             another resolves Clock on the helper's Koin.
//
// EXPECTED: no KOIN-D002 for `get<Clock>()`, one KOIN-W009 instead. Clock is loaded by the helper's graph; this
// compilation can't see that graph, so it can't prove anything about the call site.

// MODULE: testing
// FILE: testing/TestFixture.kt
package testing

import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Clock

val infraModule = module {
    single<Clock>()
}

class TestFixture {
    val koin: Koin by lazy { koinApplication { modules(infraModule) }.koin }
}

// MODULE: spec(testing)
// FILE: spec/IsolatedGraphSpec.kt
package spec

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class LocalThing

fun isolatedGraph() {
    koinApplication {
        modules(module { single<LocalThing>() })
    }
}

// FILE: spec/HelperSpec.kt
package spec

import testing.Clock
import testing.TestFixture

fun helperGraph() {
    val app = TestFixture()
    val clock = app.koin.get<Clock>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty, propertyDeclaration,
propertyDelegate */
