// RUN_PIPELINE_TILL: BACKEND
// FILE: core/Repository.kt
// Regression (review finding, MEDIUM): KOIN-W007 is emitted from CallSiteValidator.
// validateDslDefinitionGraph ("Phase 3.1 DSL-only"), which KoinIrExtension only runs when
// safetyValidator.assembledGraphTypes is still empty. A typed startKoin<T>()/@KoinApplication
// entry point populates that set via CompileSafetyValidator.validateFullGraph BEFORE Phase 3.1's
// check runs — which used to have no W007-equivalent disclosure of its own. Net effect: a typed
// entry point ANYWHERE in this compilation silently suppressed W007 for unsafe DSL usage
// EVERYWHERE else in the same compilation, even in a module the entry point never loads. Fixed by
// adding the same disclosure loop to validateFullGraph.
package core

import org.koin.core.annotation.Singleton

@Singleton
class Repository

// FILE: modules.kt
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("core")
class CoreModule

// FILE: app.kt
import org.koin.core.annotation.KoinApplication

@KoinApplication(modules = [CoreModule::class])
object MyApp

// FILE: unrelated_dsl.kt
// Never loaded by MyApp — dslDefinitions still collects it (same as any declared-but-unloaded
// module), which is what exercises the coexistence gap: this def is only reachable via the
// DSL-only path's own logic, not via MyApp's assembled graph at all.
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

class Missing
class NeedsMissing(val m: Missing)

val looseDslModule = module {
    singleOf(::NeedsMissing)
}

// FILE: test.kt
import org.koin.plugin.module.dsl.startKoin

fun useIt() {
    startKoin<MyApp> { }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, objectDeclaration,
   primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
