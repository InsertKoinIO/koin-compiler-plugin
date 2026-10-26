// RUN_PIPELINE_TILL: BACKEND
// A3 — falsify-first guard for isComplete propagation through the hint-only/classpath includes fold
// (KoinAnnotationProcessor.collectDefinitionsFromDependencyModule -> foldHintOnlyIncludes and the
// classpath includedModules loop).
//
// `mid` includes `data`, which is genuinely incomplete (its @ComponentScan targets an EMPTY package
// — no scan hints resolve to anything, so `data`'s own DependencyModuleResult.isComplete is
// correctly false). `mid` has no @ComponentScan of its own, so BEFORE the fix its own isComplete
// computation (`!hasComponentScan || scanDefinitionsFound`) ignored `data`'s incompleteness entirely
// and came back true — `feature`'s definitely-missing `SomeMissing` dependency (nothing anywhere
// provides it) then had a real KOIN-D001 raised against it, because validateFullGraph believed the
// assembled graph was fully known.
//
// That specific D001 is a FALSE POSITIVE here in the sense that matters for this test: it fires only
// because the validator wrongly trusts a partial view of the graph as complete. Per the project's own
// documented fail-open policy (CompileSafetyValidator: "we cannot prove anything missing" when any
// module is incomplete), an entry point reachable through a genuinely-incomplete module must defer
// validation entirely instead of reporting spurious diagnostics against a graph it never fully saw.
//
// EXPECTED: no KOIN-D001 — `mid`'s isComplete correctly inherits the incompleteness below it, so
// validateFullGraph's fail-open path takes over for the whole entry point. But never silently: the
// skip is disclosed as KOIN-W008 naming the unresolved module, since `SomeMissing` really is missing
// and a bare green build would imply it was checked. If this test ever reports KOIN-D001 again, the
// isComplete propagation through the includes fold has regressed; if it goes silent, the disclosure has.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

// Scans a package with ZERO annotated classes — scanDefinitionsFound comes back false, so this
// module's own DependencyModuleResult.isComplete is correctly false.
@Module
@ComponentScan("data.empty")
class DataModule

// MODULE: mid(data)
// FILE: mid/Mid.kt
package mid

import org.koin.core.annotation.Module
import data.DataModule

// No @ComponentScan of its own — before the fix, hasComponentScan=false made this module's own
// isComplete always true, regardless of what folding DataModule's incomplete result produced.
@Module(includes = [DataModule::class])
class MidModule

// MODULE: feature(mid)
// FILE: feature/Feature.kt
package feature

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Single
import mid.MidModule

class SomeMissing

@Single
class NeedsSomething(val missing: SomeMissing)

@Module(includes = [MidModule::class])
@ComponentScan("feature")
class FeatureModule

// MODULE: app(feature)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.plugin.module.dsl.startKoin
import feature.FeatureModule

@Module(includes = [FeatureModule::class])
class AppModule

@KoinApplication(modules = [AppModule::class])
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
