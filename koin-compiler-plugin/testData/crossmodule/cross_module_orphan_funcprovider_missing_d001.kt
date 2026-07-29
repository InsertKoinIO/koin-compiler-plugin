// RUN_PIPELINE_TILL: BACKEND
// A3 Gate-3 carrier cut-2 (1b) — RED for an ORPHAN-path cross-module function provider.
//
// funcreqs cut-1/1a only emit via the SCAN path (a @ComponentScan module OWNS the function). An
// ORPHAN top-level @Single fun (no @ComponentScan in its OWN module) that a DOWNSTREAM module scans
// is discovered cross-module as an ExternalFunctionDef via its definition_function_* hint — but no
// funcreqs hint is emitted for it, so its requirements reach the root empty → silent false negative.
//
// Topology:
//   depmod : @Single class Dep — provides Dep so the oracle knows it exists (prov defers); NOT
//            loaded at the app (app scans only "prov").
//   prov   : top-level @Single fun provideThing(dep: Dep): Thing — ORPHAN (no @Module/@ComponentScan
//            in prov). Emits a definition_function_single hint keyed to package "prov".
//   app    : @Module @ComponentScan("prov") + @KoinApplication + startKoin<MyApp>. Scans prov →
//            discovers provideThing as an ExternalFunctionDef. Dep is absent from the assembled graph.
//
// EXPECTED (1b): app-root KOIN-D001 Missing dependency: Dep, required by provideThing.
// RED (before 1b): no orphan funcreqs → provideThing reaches the root with empty requirements → no
// root D001 (silent).

// MODULE: depmod
// FILE: depmod/Dep.kt
package depmod

import org.koin.core.annotation.Single

@Single
class Dep

// MODULE: prov(depmod)
// FILE: prov/Prov.kt
package prov

import org.koin.core.annotation.Single
import depmod.Dep

class Thing(val dep: Dep)

@Single
fun provideThing(dep: Dep): Thing = Thing(dep)

// MODULE: app(prov)
// FILE: app/App.kt
package app

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@Module
@ComponentScan("prov")
class AppModule

@KoinApplication(modules = [AppModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
