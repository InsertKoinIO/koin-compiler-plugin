// RUN_PIPELINE_TILL: BACKEND
// an `internal` class defined in `feature` is relayed through `mid` to `app`. The relay
// hint `mid` writes must not name a type `mid` can't see (invalid IR under IR validation).
//
// EXPECTED: no compiler crash; the graph validates (no diagnostics).

// MODULE: feature
// FILE: feature/Feature.kt
package feature

import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.single

class Api
internal class DetailPresenter(val api: Api)

val featureModule = module {
    single<Api>()
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
