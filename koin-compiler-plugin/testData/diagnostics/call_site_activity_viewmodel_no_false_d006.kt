// RUN_PIPELINE_TILL: BACKEND
// GH #121: `activityViewModel<T>()` returns the instance the activity already created with its
// own `parametersOf`. Here the activity's creating call site is in the same compilation.
//
// koin-android isn't on the test classpath: the stub below has the real function's FqName and shape.
//
// EXPECTED: no KOIN-D006 for `activityViewModel<MyViewModel>()`.

// FILE: stub.kt
package org.koin.androidx.viewmodel.ext.android

import org.koin.core.parameter.ParametersHolder

inline fun <reified T : Any> activityViewModel(
    noinline parameters: (() -> ParametersHolder)? = null,
): Lazy<T> = lazy { TODO() }

inline fun <reified T : Any> viewModel(
    noinline parameters: (() -> ParametersHolder)? = null,
): Lazy<T> = lazy { TODO() }

// FILE: test.kt
package testpkg

import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Module
import org.koin.core.context.startKoin
import org.koin.core.parameter.parametersOf

@Module
@ComponentScan("testpkg")
class TestModule

@Factory
class MyViewModel(@InjectedParam val url: String)

fun useIt() {
    startKoin { modules(TestModule().module()) }
}

class MyActivity {
    val viewModel by viewModel<MyViewModel> { parametersOf("https://insert-koin.io") }
}

class MyFragment {
    val viewModel by activityViewModel<MyViewModel>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, inline, lambdaLiteral, noinline,
nullableType, primaryConstructor, propertyDeclaration, propertyDelegate, reified, stringLiteral, typeConstraint,
typeParameter */
