// RUN_PIPELINE_TILL: BACKEND
// an includes() cycle across hops: the walk terminates.
//
// EXPECTED: exactly one KOIN-D001.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Missing
class Repo(val missing: Missing)

fun d1(): Module = module { includes(d2()); single<Repo>() }
fun d2(): Module = module { includes(d1()) }

// MODULE: feature(data)
// FILE: feature/Feature.kt
package feature

import data.d1
import org.koin.dsl.module

val featureModule = module { includes(d1()) }

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
