// RUN_PIPELINE_TILL: BACKEND
// Safe direction: `feature` is built like a plugin from before the completion marker, so its
// empty scan is unproven. The consumer must keep withholding validation, disclosed as KOIN-W008.
//
// EXPECTED: KOIN-W008 for feature.FeatureModule (and no KOIN-D001).

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
// OMIT_SCAN_COMPLETION_MARKERS
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
class Screen(val repository: Repository, val missing: Missing)

class Missing

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
