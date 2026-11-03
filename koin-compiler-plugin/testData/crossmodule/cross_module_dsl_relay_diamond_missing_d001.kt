// RUN_PIPELINE_TILL: BACKEND
// two relay paths to the same far module (diamond).
//
// EXPECTED: exactly one KOIN-D001.

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

// MODULE: midA(feature)
// FILE: midA/MidA.kt
package midA

import feature.featureModule
import org.koin.dsl.module

val midAModule = module { includes(featureModule) }

// MODULE: midB(feature)
// FILE: midB/MidB.kt
package midB

import feature.featureModule
import org.koin.dsl.module

val midBModule = module { includes(featureModule) }

// MODULE: app(midA, midB)
// FILE: app/App.kt
package app

import midA.midAModule
import midB.midBModule
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class AppThing

fun main() {
    startKoin { modules(module { single<AppThing>() }, midAModule, midBModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
