// RUN_PIPELINE_TILL: BACKEND
// same unreadable includes() argument, three hops away.
//
// EXPECTED: KOIN-W003, no KOIN-D001.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Missing
class Repo(val missing: Missing)

val extras: List<Module> = listOf(module { })

val dataModule = module {
    includes(extras.first())
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
