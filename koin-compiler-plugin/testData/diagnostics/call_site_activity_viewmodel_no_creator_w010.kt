// RUN_PIPELINE_TILL: BACKEND
// GH #121: `activityViewModel<T>()` only reuses an instance if something created it with
// `parametersOf`. Nothing here does; the creator may be in another module, so this can't be an
// error, but it must not be silent either.
//
// koin-android isn't on the test classpath: the stub below has the real function's FqName and shape.
//
// EXPECTED: no KOIN-D006, one KOIN-W010 for `activityViewModel<MyViewModel>()`.

// FILE: stub.kt
package org.koin.androidx.viewmodel.ext.android

import org.koin.core.parameter.ParametersHolder

inline fun <reified T : Any> activityViewModel(
    noinline parameters: (() -> ParametersHolder)? = null,
): Lazy<T> = lazy { TODO() }

// FILE: test.kt
package testpkg

import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Module
import org.koin.core.context.startKoin

@Module
@ComponentScan("testpkg")
class TestModule

@Factory
class MyViewModel(@InjectedParam val url: String)

fun main() {
    startKoin { modules(TestModule().module()) }
}

fun fragment() {
    val viewModel = activityViewModel<MyViewModel>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, inline, lambdaLiteral, localProperty,
noinline, nullableType, primaryConstructor, propertyDeclaration, reified, stringLiteral, typeConstraint, typeParameter */
