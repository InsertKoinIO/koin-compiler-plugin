// RUN_PIPELINE_TILL: BACKEND
// four-module annotation chain; app needs the public Repo, bound only by an internal
// RepoImpl in data. The binding must survive two relays of an erased class.
//
// EXPECTED: no diagnostics.

// MODULE: contracts
// FILE: contracts/Repo.kt
package contracts

interface Repo

// MODULE: data(contracts)
// FILE: data/Data.kt
package data

import contracts.Repo
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Single
internal class RepoImpl : Repo

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

// MODULE: mid(feature)
// FILE: mid/Mid.kt
package mid

import feature.FeatureModule
import org.koin.core.annotation.Module

@Module(includes = [FeatureModule::class])
class MidModule

// MODULE: app(mid, contracts)
// FILE: app/App.kt
package app

import contracts.Repo
import mid.MidModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

@Single
class Screen(val repo: Repo)

@Module(includes = [MidModule::class])
@ComponentScan("app")
class AppModule

@KoinApplication(modules = [AppModule::class])
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, interfaceDeclaration,
lambdaLiteral, objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
