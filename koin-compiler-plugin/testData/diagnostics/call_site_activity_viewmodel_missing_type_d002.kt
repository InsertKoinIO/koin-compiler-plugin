// RUN_PIPELINE_TILL: BACKEND
// GH #121 guard: skipping the parametersOf shape check for `activityViewModel` must not skip the
// type check. A ViewModel nothing defines is still missing.
//
// EXPECTED: KOIN-D002 for `activityViewModel<Undefined>()`.

// FILE: stub.kt
package org.koin.androidx.viewmodel.ext.android

import org.koin.core.parameter.ParametersHolder

inline fun <reified T : Any> activityViewModel(
    noinline parameters: (() -> ParametersHolder)? = null,
): Lazy<T> = lazy { TODO() }

// FILE: test.kt
package testpkg

import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Present
class Undefined

fun main() {
    startKoin { modules(module { single<Present>() }) }
}

fun fragment() {
    val viewModel = activityViewModel<Undefined>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, inline, lambdaLiteral, noinline,
nullableType, propertyDeclaration, propertyDelegate, reified, typeConstraint, typeParameter */
