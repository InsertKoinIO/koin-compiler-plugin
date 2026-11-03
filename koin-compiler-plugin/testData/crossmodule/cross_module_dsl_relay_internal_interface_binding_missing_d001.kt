// RUN_PIPELINE_TILL: BACKEND
// Presenter needs the internal Store; StoreImpl is registered WITHOUT bind Store::class.
// app must report the missing Store two hops away (erased requirement, nothing provides it).
//
// EXPECTED: KOIN-D001 for feature.Store.

// MODULE: feature
// FILE: feature/Feature.kt
package feature

import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.single

internal interface Store
internal class StoreImpl : Store
class Presenter internal constructor(internal val store: Store)

val featureModule = module {
    single<StoreImpl>()
    factory<Presenter>()
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

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral, primaryConstructor,
propertyDeclaration */
