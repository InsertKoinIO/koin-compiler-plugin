// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — baseline matrix (salvaged from the A2 collect-only spike).
//
// A component-scanned @Singleton CLASS in one @Configuration label depends on a
// component-scanned @Singleton CLASS in a *different* label, and BOTH modules are
// assembled at a typed @KoinApplication / startKoin<T> entry point. The graph is
// therefore COMPLETE: Repository (CoreModule) resolves for Service (ServiceModule) at
// the A3 full-graph pass.
//
// TARGET (Step 5, A2→structural-only): empty .errors.txt (no diagnostic).
// BASELINE (shipping code): emits KOIN-D001 — a FALSE POSITIVE. The A2 per-module pass
// validates ServiceModule in isolation; Repository is a component-scanned Definition.ClassDef
// in a different label, absent from ServiceModule's A2 visibility set AND excluded from the
// cross-module provider-hint oracle (KoinAnnotationProcessor.kt:175), so A2 hard-errors even
// though the typed A3 pass would resolve it. This golden captures the bug; Step 5 flips it to
// empty as a reviewed diff.
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
