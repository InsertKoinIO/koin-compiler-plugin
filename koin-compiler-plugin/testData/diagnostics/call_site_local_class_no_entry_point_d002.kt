// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #101: an unresolved call site on a local class (no ClassId) in a compilation with no entry point
// used to be deferred as a call-site hint, crashing with "No classId for IrClassImpl". No other
// module can ever provide a local type, so it must fail here with KOIN-D002 instead.
//
// EXPECTED: KOIN-D002 for the local class, no crash.
package testpkg

import org.koin.core.Koin

fun useIt(koin: Koin) {
    class LocalOnly
    val x = koin.get<LocalOnly>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, localClass, localProperty, propertyDeclaration */
