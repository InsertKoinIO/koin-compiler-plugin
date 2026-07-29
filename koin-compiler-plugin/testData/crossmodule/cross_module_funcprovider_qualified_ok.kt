// RUN_PIPELINE_TILL: BACKEND
// A3 Gate-3 carrier cut-2 — POSITIVE guard: a QUALIFIED cross-module function requirement that IS
// satisfied must resolve SILENTLY (no false KOIN-D001 from a mis-encoded/mis-decoded qualifier).
//
// Falsify-first companion to cross_module_funcprovider_qualified_missing_d001: that test proves the
// carried qualifier makes a MISSING @Named dep fire D001; this one proves the SAME carrier does NOT
// over-error when the qualified provider is present — i.e. the round-tripped @Named("db") on the
// requirement matches the @Named("db") on the provider.
//
// lib (@ComponentScan("lib")) provides BOTH the qualified provider and its qualified consumer as
// top-level @Single functions. app loads LibModule, so at the A3 full-graph pass provideService's
// carried @Named("db") Repository requirement resolves against provideRepository's @Named("db")
// Repository. EXPECTED: empty .errors.txt.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Single
import org.koin.core.annotation.Named

class Repository
class Service(val repo: Repository)

@Single
@Named("db")
fun provideRepository(): Repository = Repository()

@Single
fun provideService(@Named("db") repo: Repository): Service = Service(repo)

@Module
@ComponentScan("lib")
class LibModule

// MODULE: app(lib)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin
import lib.LibModule

@KoinApplication(modules = [LibModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
