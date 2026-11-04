// RUN_PIPELINE_TILL: BACKEND
// an aggregator @Module whose @ComponentScan package holds no definitions must not make
// the whole entry point unverifiable. "Scanned, found nothing" is complete, not "hints missing".
//
// EXPECTED: no diagnostics (the graph is complete and correct).

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Single
class Repository

@Module
@ComponentScan("data")
class DataModule

// MODULE: feature(data)
// FILE: feature/Feature.kt
package feature

import data.DataModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [DataModule::class])
@ComponentScan("feature")
class FeatureModule

// MODULE: app(feature, data)
// FILE: app/App.kt
package app

import data.Repository
import feature.FeatureModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

@Single
class Screen(val repository: Repository)

@Module(includes = [FeatureModule::class])
@ComponentScan("app")
class AppModule

@KoinApplication(modules = [AppModule::class])
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
