// RUN_PIPELINE_TILL: BACKEND
// Annotation path: `feature` re-publishes `data`'s scanned definitions for `app`, two hops
// away (`app` sees only `feature` and `contracts`). `data` holds an `internal` class bound to a public
// interface, with an `internal` constructor parameter. The relay must not name the internal types in
// `feature`'s own IR, and the binding must still provide Repo. (Requirements of annotated classes two
// hops away are not checked at `app`.)
//
// EXPECTED: no compiler crash; the graph validates (no diagnostics).

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
internal class Cache

@Single
internal class RepoImpl(val cache: Cache) : Repo

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

// MODULE: app(feature, contracts)
// FILE: app/App.kt
package app

import contracts.Repo
import feature.FeatureModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

@Single
class Screen(val repo: Repo)

@Module(includes = [FeatureModule::class])
@ComponentScan("app")
class AppModule

@KoinApplication(modules = [AppModule::class])
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, interfaceDeclaration, primaryConstructor,
propertyDeclaration, stringLiteral */
