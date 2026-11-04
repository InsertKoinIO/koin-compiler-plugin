// RUN_PIPELINE_TILL: BACKEND
// Falsify partner: the relay of an `internal` class must still carry its requirements.
// `Api` is provided by nobody, so `app` must report it, two hops from `feature`.
//
// EXPECTED: KOIN-D001 for feature.Api, required by feature.DetailPresenter.

// MODULE: feature
// FILE: feature/Feature.kt
package feature

import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory

class Api
internal class DetailPresenter(val api: Api)

val featureModule = module {
    factory<DetailPresenter>()
}

// MODULE: mid(feature)
// FILE: mid/Mid.kt
package mid

import feature.featureModule
import org.koin.dsl.module

val midModule = module { includes(featureModule) }

// MODULE: app(mid)
// FILE: app/App.kt
package app

import mid.midModule
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class AppThing

fun main() {
    startKoin { modules(module { single<AppThing>() }, midModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
