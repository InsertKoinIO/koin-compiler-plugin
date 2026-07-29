// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// D005/D006 must not require a Koin entry point (1.1.0): the parametersOf(...) shape check isn't
// graph-dependent (slots come from the target's own constructor), unlike KOIN-D002 (call-site
// resolution), which correctly needs an assembled graph and stays silent when there is none. This
// file has NO startKoin / koinApplication / @KoinApplication anywhere.
//
// Mechanism actually exercised: with no entry point, CallSiteValidator's `!hasFullGraph` fallback
// (CallSiteValidator.kt ~94-99) folds every locally-scanned annotation definition — including
// Greeter, discovered via TestModule's @ComponentScan — into `allKnownTypes`, so the call site
// resolves on the very first check (line 131), not via the separate `hasAnnotation` heuristic
// (line 138-148, only reached when the local module doesn't already know the type). Either way,
// the D005/D006 shape check that follows (line 133/145) is the same call, unconditional on
// having a full graph — that's the behavior this test protects.
//
// EXPECTED: only KOIN-D005 fires, no KOIN-D002.
package testpkg

import org.koin.core.Koin
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.parameter.parametersOf

@Module
@ComponentScan("testpkg")
class TestModule

@Factory
class Greeter(@InjectedParam val name: String)

fun useIt(koin: Koin) {
    val g = koin.get<Greeter> { parametersOf("hello", "extra") }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty,
   primaryConstructor, propertyDeclaration, stringLiteral */
