// RUN_PIPELINE_TILL: BACKEND
// an internal implementation bound to an internal interface; both erased in the relay.
// The erased binding must still provide Store two hops away.
//
// EXPECTED: no diagnostics.

// MODULE: feature
// FILE: feature/Feature.kt
package feature

import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.single

internal interface Store
internal class StoreImpl : Store
class Presenter internal constructor(internal val store: Store)

val featureModule = module {
    single<StoreImpl>() bind Store::class
    factory<Presenter>()
}

// MODULE: mid(feature)
// FILE: mid/Mid.kt
package mid

import feature.featureModule
import org.koin.dsl.bind
import org.koin.dsl.module

val midModule = module { includes(featureModule) }

// MODULE: app(mid)
// FILE: app/App.kt
package app

import mid.midModule
import org.koin.core.context.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class AppThing

fun main() {
    startKoin { modules(module { single<AppThing>() }, midModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration, lambdaLiteral,
outProjection, primaryConstructor, propertyDeclaration */
