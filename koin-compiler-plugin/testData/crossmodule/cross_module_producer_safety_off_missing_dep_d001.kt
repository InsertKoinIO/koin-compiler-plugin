// RUN_PIPELINE_TILL: BACKEND
// Regression test: a PRODUCER module's own koinCompiler { compileSafety = false } must not make a
// DOWNSTREAM consumer's validation silently miss a real missing dependency. A short-lived earlier
// version of this branch gated funcreqs_*/annotationincludes_* emission on the LOCAL
// compileSafetyEnabled flag — wrong, because visibility of these hints depends on whether a
// downstream consumer validates through this module, not on whether the producer itself does.
//
// Topology (same shape as cross_module_orphan_funcprovider_missing_d001.kt, + DISABLE_COMPILE_SAFETY
// on prov):
//   depmod : @Single class Dep — provides Dep so the oracle knows it exists; NOT loaded at the app
//            (app scans only "prov"), so it's genuinely absent from app's assembled graph.
//   prov   : compileSafety = false. Top-level @Single fun provideThing(dep: Dep): Thing — ORPHAN
//            (no @Module/@ComponentScan in prov). Emits a definition_function_single hint keyed to
//            package "prov" regardless of prov's own compileSafety setting (that hint was never
//            gated), but its REQUIREMENTS carrier (funcreqs_*) is the thing under test here.
//   app    : @Module @ComponentScan("prov") + @KoinApplication + startKoin<MyApp>, compileSafety
//            default (true). Scans prov → discovers provideThing as an ExternalFunctionDef.
//
// EXPECTED (funcreqs_* unconditional): app-root KOIN-D001 Missing dependency: Dep, required by
// provideThing — proving app's validator still sees Thing's real requirement on Dep even though
// prov (the producer) disabled compile safety for itself.
// RED (gated-on-local-setting bug): prov's compileSafety=false skips emitOrphanFuncReqsHints
// entirely → provideThing's ExternalFunctionDef reaches app with EMPTY requirements → no D001 —
// app's build reports clean when it shouldn't (silent false negative).

// MODULE: depmod
// FILE: depmod/Dep.kt
package depmod

import org.koin.core.annotation.Single

@Single
class Dep

// MODULE: prov(depmod)
// DISABLE_COMPILE_SAFETY
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
