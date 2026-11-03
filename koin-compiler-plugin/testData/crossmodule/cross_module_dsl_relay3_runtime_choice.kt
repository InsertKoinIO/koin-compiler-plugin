// RUN_PIPELINE_TILL: BACKEND
// same runtime choice, three hops away.
//
// EXPECTED: KOIN-W011 and KOIN-D001, as at two hops.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Missing
class Repo(val missing: Missing)

val aModule = module { single<Repo>() }
val bModule = module { }

fun dataModule(local: Boolean): Module = if (local) aModule else bModule

// MODULE: feature(data)
// FILE: feature/Feature.kt
package feature

import data.dataModule
import org.koin.dsl.module

val featureModule = module { includes(dataModule(true)) }

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

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, ifExpression, lambdaLiteral, primaryConstructor,
propertyDeclaration */
