// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — scoped A2→A3 authority shift (false positive FIXED).
//
// A component-scanned @Singleton CLASS in one @Configuration label depends on a
// component-scanned @Singleton CLASS in a *different* label, and BOTH modules are
// assembled at a typed @KoinApplication / startKoin<T> entry point. The graph is
// therefore COMPLETE: Repository (CoreModule) resolves for Service (ServiceModule) at
// the A3 full-graph pass.
//
// RESULT: empty .errors.txt (no diagnostic) — correct. A2 validates ServiceModule in
// isolation and can't see Repository (a scanned Definition.ClassDef in a different label),
// but the cross-module provider-hint oracle now INCLUDES scanned classes, so A2 DEFERS
// instead of hard-erroring; the typed A3 full-graph pass then assembles both modules and
// resolves Repository, settling the deferral silently. (Before the shift this was a FALSE
// POSITIVE KOIN-D001 — the oracle excluded ClassDef, so A2 hard-errored a valid graph.)
// FILE: core/Repository.kt
package core

import org.koin.core.annotation.Singleton

@Singleton
class Repository

// FILE: service/Service.kt
package service

import core.Repository
import org.koin.core.annotation.Singleton

@Singleton
class Service(val repo: Repository)

// FILE: modules.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@Module
@ComponentScan("core")
@Configuration("core")
class CoreModule

@Module
@ComponentScan("service")
@Configuration("service")
class ServiceModule

// The entry point assembles BOTH labeled modules — the graph is complete at A3.
@KoinApplication(modules = [CoreModule::class, ServiceModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, lambdaLiteral, objectDeclaration, primaryConstructor, propertyDeclaration */
