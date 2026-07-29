// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression test for a real bug found during 1.1.0 release verification (playground
// app-annotations, StressTestApplication/FeaturesModule): a plain `@Module @ComponentScan` class
// with NO `@Configuration` and NOT referenced by anyone's `includes = [...]` must NOT be treated
// as reachable from a bare (default-labeled) entry point.
//
// Root cause (now fixed): KoinStartTransformer.hasConfigurationWithMatchingLabels called a
// private extractConfigurationLabels(IrClass) that actually read @KoinApplication's
// `configurations` argument (meant for the ENTRY-POINT class), not @Configuration. Since a module
// class never carries @KoinApplication, that lookup always fell through to its "not found"
// fallback (["default"]) — so EVERY @Module class in the same compilation silently matched the
// "default" label, regardless of whether it had @Configuration or was included by anyone. That
// made OrphanModule below (and its ComponentScan-discovered OrphanDepImpl) incorrectly part of
// the resolved graph, silently satisfying Consumer's dependency — a false negative that would
// build clean and crash at runtime, now much more consequential since A2 (which used to validate
// OrphanModule in isolation too) was removed and A3 is the sole verifier.
//
// EXPECTED: KOIN-D001 — OrphanDep is genuinely unreachable (OrphanModule is orphaned: no
// @Configuration, not included by AppModule or anyone else).
package testpkg

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton
import org.koin.plugin.module.dsl.startKoin

interface OrphanDep

// Deliberately orphaned: @Module + @ComponentScan only, no @Configuration, never included.
@Module
@ComponentScan("testpkg.orphan")
class OrphanModule

@Module
@ComponentScan("testpkg.consumer")
@Configuration
class AppModule

@KoinApplication
object MyApp

fun start() {
    startKoin<MyApp> {}
}

// FILE: orphan/OrphanDepImpl.kt
package testpkg.orphan

import testpkg.OrphanDep
import org.koin.core.annotation.Singleton

@Singleton
class OrphanDepImpl : OrphanDep

// FILE: consumer/Consumer.kt
package testpkg.consumer

import testpkg.OrphanDep
import org.koin.core.annotation.Singleton

@Singleton
class Consumer(val dep: OrphanDep)

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral,
   objectDeclaration, primaryConstructor, propertyDeclaration */
