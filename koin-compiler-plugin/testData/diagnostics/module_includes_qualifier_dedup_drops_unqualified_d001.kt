// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression (issue #94): a @Module(includes=[...]) chain where two included modules provide the
// SAME type under different qualifiers (one qualified, one not) must keep BOTH — a consumer
// requiring the unqualified one must still see it.
//
// KoinAnnotationProcessor.getDefinitionsForModule (the A3 full-graph collector for a specific
// entry point) folds each included module's definitions into the running set using
// `existingFqNames: Set<FqName>` — keyed on `returnTypeClass.fqNameWhenAvailable` ALONE, with no
// qualifier. ModuleA is walked first and registers HttpClient's FqName; ModuleB's UNQUALIFIED
// HttpClient definition is then filtered out entirely as "already known", even though it's the
// one ModuleB.provideService actually needs. Confirmed via tracing: RootModule's resolved set for
// this entry point ends up as [HttpClient(@Named("a")), Service] — the unqualified HttpClient
// provider is gone — so Service's requirement is falsely reported as unsatisfied:
//
//   [Koin][KOIN-D001] Missing dependency: HttpClient
//     required by: ModuleB.provideService() (parameter 'client')
//     in module: ModuleB
//     Hint: Found similar binding: HttpClient with qualifier @Named("a")
//
// Runtime resolves this fine — Koin's own includes() merges both real single{} bodies (confirmed:
// the equivalent shape as a box test, ignoring compile-safety, returns "OK"). This is a
// compile-time-only false positive on a valid graph.
package testpkg

import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

interface HttpClient
class HttpClientA : HttpClient
class HttpClientDefault : HttpClient
class Service(val client: HttpClient)

@Module
class ModuleA {
    @Single
    @Named("a")
    fun provideClientA(): HttpClient = HttpClientA()
}

@Module
class ModuleB {
    @Single
    fun provideClient(): HttpClient = HttpClientDefault()

    @Single
    fun provideService(client: HttpClient): Service = Service(client)
}

@Module(includes = [ModuleA::class, ModuleB::class])
class RootModule

@KoinApplication(modules = [RootModule::class])
object MyApp

fun useIt() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, interfaceDeclaration,
lambdaLiteral, objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
