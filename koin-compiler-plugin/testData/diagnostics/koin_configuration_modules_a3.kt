// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression test for koin-compiler-plugin#38.
//
// Before the fix, bare `koinConfiguration { modules(M::class) }` (no `<T>` — the form
// used by the Compose `KoinApplication(configuration = koinConfiguration { … })`
// composable entry) bailed out at `KoinStartTransformer.kt:144` and never reached A3
// full-graph validation. A `@Module` with a missing dependency would compile cleanly
// and crash at runtime.
//
// After the fix: when the entry has no `<T>`, walk the trailing lambda for
// `KoinApplication.modules(vararg KClass)` calls and trigger A3 with whatever module
// classes it finds. Here we wire a deliberately-incomplete graph (`Service` needs
// `Repo`, `Repo` is never registered) so A3 must fire `[Koin][KOIN-D001] Missing
// dependency: testpkg.Repo` to fail the build.
package testpkg

import org.koin.dsl.koinConfiguration
import org.koin.plugin.module.dsl.modules
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

class Repo

@Singleton
class Service(val repo: Repo)

@Module
@ComponentScan
class AppModule

fun setup() {
    koinConfiguration {
        modules(AppModule::class)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral,
   primaryConstructor, propertyDeclaration */
