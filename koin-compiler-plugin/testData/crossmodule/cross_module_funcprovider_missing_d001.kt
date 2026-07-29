// RUN_PIPELINE_TILL: BACKEND
// A3 Gate-3 carrier — RED baseline for the cross-module TOP-LEVEL @Single function-provider gap.
//
// SCOPE (empirically established 2026-07-23): the ExternalFunctionDef empty-requirements gap hits
// ONLY top-level `@Single fun` discovered cross-module via @ComponentScan roster hints — these are
// reconstructed as ExternalFunctionDef with requirements = emptyList() (KoinAnnotationProcessor.kt
// :2278), so A3 at the root is blind to what they need. (A @Module-MEMBER function reached via
// @KoinApplication(modules=[X]) resolves as a real member function with params read from ABI and IS
// validated at the root today.)
//
// Topology (genuine `// MODULE:` separation — required to reach the ExternalFunctionDef path):
//   base : top-level @Single fun provideRepository(): Repository — an ORPHAN definition (no @Module /
//          @ComponentScan in base), so base emits a `definition_function_single` discovery hint. lib
//          depends on base, so lib's A2 oracle finds that hint → DEFERS its Repository requirement
//          (no false-positive D001 at lib). NB: a @ComponentScan-scanned CLASS provider would emit a
//          `componentscan_*` hint the oracle does not currently recognize cross-module — a separate
//          provider-side gap deliberately kept out of this probe by using an orphan function here.
//   lib  : top-level @Single fun provideService(repo: Repository): Service, scanned by LibModule.
//   app  : @KoinApplication(modules = [LibModule]) + startKoin<MyApp>. Loads ONLY LibModule (scans
//          "lib"). base's orphan provideRepository is NOT loaded (base has no module, app doesn't
//          scan "base") → Repository is genuinely MISSING in the assembled graph.
//
// GREEN (carrier landed — this golden): base's funcreqs_lib_Service carrier hint carries
// provideService's `repo: Repository` requirement across the module boundary; the consumer rebuilds
// ExternalFunctionDef.requirements from it, so A3 at the app root validates provideService and emits
// KOIN-D001 Missing dependency: base.Repository, in module MyApp (startKoin). Regression guard:
// before the carrier this file was RED (a silent false negative → runtime crash). 1.1.0: lib no
// longer also emits a KOIN-W002 leaf deferral (A2 removed) — the root D001 alone is now sufficient.

// MODULE: base
// FILE: base/Base.kt
package base

import org.koin.core.annotation.Single

class Repository

// Orphan top-level definition (no @Module/@ComponentScan in this module) → emits a
// definition_function_single discovery hint the downstream oracle can see.
@Single
fun provideRepository(): Repository = Repository()

// MODULE: lib(base)
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Single
import base.Repository

class Service(val repo: Repository)

@Single
fun provideService(repo: Repository): Service = Service(repo)

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
