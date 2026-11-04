// RUN_PIPELINE_TILL: BACKEND
// five-module DSL includes() chain (`app` → `top` → `mid` → `feature` → `data`): relays of
// relays must keep both the definitions and the includes() edges, four hops away.
//
// EXPECTED: KOIN-D001 for data.Missing.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Missing
class Repo(val missing: Missing)

val dataModule = module {
    single<Repo>()
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

// MODULE: top(mid)
// FILE: top/Top.kt
package top

import mid.midModule
import org.koin.dsl.module

val topModule = module { includes(midModule) }

// MODULE: app(top)
// FILE: app/App.kt
package app

import top.topModule
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class AppThing

fun main() {
    startKoin { modules(module { single<AppThing>() }, topModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
