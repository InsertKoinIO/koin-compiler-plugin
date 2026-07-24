// RUN_PIPELINE_TILL: BACKEND
// A3 Gate-3 carrier cut-2 — RED for a QUALIFIED cross-module function-provider requirement.
//
// Same shape as cross_module_funcprovider_missing_d001, but lib's top-level @Single function needs
// its dependency with a @Named qualifier. Cut-1 SKIPPED the whole funcreqs hint whenever any
// requirement was qualified, so provideService reached the app root as a requirements-empty
// ExternalFunctionDef → the missing @Named("db") Repository was NEVER validated → silent false
// negative. Cut-2 encodes per-requirement qualifiers in the funcreqs hint, so A3 at the root now
// emits KOIN-D001 for the missing qualified dependency.
//
// Topology (genuine `// MODULE:` separation → ExternalFunctionDef path):
//   base : top-level @Single fun provideRepository(): Repository — ORPHAN (no @ComponentScan), so it
//          emits a definition_function_single hint. The oracle sees Repository exists on the graph,
//          so lib DEFERS its @Named("db") Repository requirement (no false positive at lib). base is
//          NOT loaded at the app (no module, app doesn't scan "base").
//   lib  : top-level @Single fun provideService(@Named("db") repo: Repository): Service, scanned by
//          LibModule. The requirement is QUALIFIED.
//   app  : @KoinApplication(modules = [LibModule]) + startKoin<MyApp>. Loads ONLY LibModule.
//          Repository @Named("db") is genuinely absent from the assembled graph.
//
// EXPECTED (cut-2): app-root KOIN-D001 Missing dependency: Repository qualified with @Named("db"),
// required by provideService.
// RED (cut-1): empty .errors.txt except lib's W002 deferral (no qualified-aware root D001).

// MODULE: base
// FILE: base/Base.kt
package base

import org.koin.core.annotation.Single

class Repository

@Single
fun provideRepository(): Repository = Repository()

// MODULE: lib(base)
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Single
import org.koin.core.annotation.Named
import base.Repository

class Service(val repo: Repository)

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
