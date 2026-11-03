// RUN_PIPELINE_TILL: BACKEND
// four-module DSL includes() chain with an internal type qualifier in the far module: the
// second relay re-encodes an already-erased class.
//
// EXPECTED: no diagnostics.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.qualifier.named
import org.koin.dsl.module

internal class Tag
class Api

val dataModule = module {
    single(named<Tag>()) { Api() }
}

// MODULE: feature(data)
// FILE: feature/Feature.kt
package feature

import data.dataModule
import org.koin.dsl.module

val featureModule = module { includes(dataModule) }

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

/* GENERATED_FIR_TAGS: classDeclaration, lambdaLiteral, propertyDeclaration */
