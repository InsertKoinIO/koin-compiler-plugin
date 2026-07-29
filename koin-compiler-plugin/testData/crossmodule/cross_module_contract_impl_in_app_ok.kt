// RUN_PIPELINE_TILL: BACKEND
// Distinct from cross_module_peer_provider_ok.kt: there the impl lives in a separate THIRD
// library module (`peer`). Here the feature module declares the contract (interface) and
// consumes it, but the concrete impl is provided directly by the ROOT `:app` module itself —
// the common Android/KMP shape where a feature can't depend on `:app` (that dependency would be
// backwards), so the impl can only ever be unified with its consumer at the entry point.
//
// Note: this exercises the same assembled-graph/BindingRegistry code path as
// cross_module_peer_provider_ok.kt (AppModule's own @ComponentScan discovers AnalyticsBackendImpl
// exactly like any local @Singleton) — it adds domain-shape coverage for a very common topology,
// not a new code path.
//
// EXPECTED: empty .errors.txt — AnalyticsBackend resolves from `:app`'s own definition once the
// graph is assembled at MyApp.

// MODULE: feature
// FILE: feature/AnalyticsBackend.kt
package feature

interface AnalyticsBackend {
    fun track(event: String)
}

// FILE: feature/AnalyticsService.kt
package feature

import org.koin.core.annotation.Singleton

// Needs AnalyticsBackend, but this feature module has (and can have) no Gradle dependency on
// :app — the impl only exists there.
@Singleton
class AnalyticsService(val backend: AnalyticsBackend)

// FILE: feature/FeatureModule.kt
package feature

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

@Module
@ComponentScan("feature")
class FeatureModule

// MODULE: app(feature)
// FILE: app/AnalyticsBackendImpl.kt
package app

import feature.AnalyticsBackend
import org.koin.core.annotation.Singleton

@Singleton
class AnalyticsBackendImpl : AnalyticsBackend {
    override fun track(event: String) = println(event)
}

// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.plugin.module.dsl.startKoin
import feature.FeatureModule

@Module
@ComponentScan("app")
class AppModule

@KoinApplication(modules = [FeatureModule::class, AppModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, interfaceDeclaration,
lambdaLiteral, objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
