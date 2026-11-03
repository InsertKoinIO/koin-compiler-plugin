// RUN_PIPELINE_TILL: BACKEND
// a module three hops away that nothing loads.
//
// EXPECTED: KOIN-W001 for it.

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
val otherMidModule = module { }

// MODULE: app(mid)
// FILE: app/App.kt
package app

import mid.otherMidModule
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class AppThing

fun main() {
    startKoin { modules(module { single<AppThing>() }, otherMidModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
