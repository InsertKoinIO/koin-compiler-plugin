// RUN_PIPELINE_TILL: BACKEND
// three internal definitions erase to the same relay signature; they must stay distinct
// (duplicate signatures are invalid on JVM and KLIB).
//
// EXPECTED: no diagnostics.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Single
internal class A

@Single
internal class B

@Single
internal class C

@Module
@ComponentScan("data")
class DataModule

// MODULE: feature(data)
// FILE: feature/Feature.kt
package feature

import data.DataModule
import org.koin.core.annotation.Module

@Module(includes = [DataModule::class])
class FeatureModule

// MODULE: app(feature)
// FILE: app/App.kt
package app

import feature.FeatureModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

@Single
class Screen

@Module(includes = [FeatureModule::class])
@ComponentScan("app")
class AppModule

@KoinApplication(modules = [AppModule::class])
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, stringLiteral */
