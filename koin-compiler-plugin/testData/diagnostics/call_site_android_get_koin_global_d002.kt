// RUN_PIPELINE_TILL: BACKEND
// #58 guard: Android's `getKoin()` is the global context, so where `startKoin` is called a
// call on it is still judged.
//
// koin-android isn't on the test classpath: the stub below has the real function's FqName.
//
// EXPECTED: KOIN-D002 for `get<Missing>()`.

// FILE: stub.kt
package org.koin.android.ext.android

import org.koin.core.Koin

fun Any.getKoin(): Koin = TODO()

// FILE: test.kt
package testpkg

import org.koin.android.ext.android.getKoin
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class LocalThing
class Missing

fun main() {
    startKoin { modules(module { single<LocalThing>() }) }
}

class MyActivity {
    fun load(): Missing = getKoin().get<Missing>()
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral */
