// RUN_PIPELINE_TILL: BACKEND
// the missing dependency is itself internal, so its requirement type is erased in the relay.
// app must still decode it and report it two hops away.
//
// EXPECTED: KOIN-D001 for feature.Cache.

// MODULE: feature
// FILE: feature/Feature.kt
package feature

import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory

internal class Cache
class Presenter internal constructor(internal val cache: Cache)

val featureModule = module {
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

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
