// RUN_PIPELINE_TILL: BACKEND
// GH #121, multi-module: the activity that creates the ViewModel with `parametersOf` is in `app`,
// the fragment calling `activityViewModel()` in `feature`. `feature` can't see the creator, so it
// can't tell reuse from a missing `parametersOf`.
//
// koin-android isn't on the test classpath: the stub below has the real functions' FqNames and shapes.
//
// EXPECTED: no KOIN-D006, one KOIN-W010 for `activityViewModel<MyViewModel>()`.

// MODULE: feature
// FILE: feature/Stub.kt
package org.koin.androidx.viewmodel.ext.android

import org.koin.core.parameter.ParametersHolder

inline fun <reified T : Any> activityViewModel(
    noinline parameters: (() -> ParametersHolder)? = null,
): Lazy<T> = lazy { TODO() }

inline fun <reified T : Any> viewModel(
    noinline parameters: (() -> ParametersHolder)? = null,
): Lazy<T> = lazy { TODO() }

// FILE: feature/Feature.kt
package feature

import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Module

@Module
@ComponentScan("feature")
class FeatureModule

@Factory
class MyViewModel(@InjectedParam val url: String)

fun fragment() {
    val viewModel = activityViewModel<MyViewModel>()
}

// MODULE: app(feature)
// FILE: app/App.kt
package app

import feature.FeatureModule
import feature.MyViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.annotation.KoinApplication
import org.koin.core.parameter.parametersOf
import org.koin.plugin.module.dsl.startKoin

@KoinApplication(modules = [FeatureModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

fun activity() {
    val viewModel = viewModel<MyViewModel> { parametersOf("https://insert-koin.io") }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, functionalType, inline,
lambdaLiteral, localProperty, noinline, nullableType, objectDeclaration, primaryConstructor, propertyDeclaration,
reified, stringLiteral, typeConstraint, typeParameter */
