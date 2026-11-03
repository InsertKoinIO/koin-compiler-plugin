// RUN_PIPELINE_TILL: BACKEND
// Sibling path: app's @ComponentScan("shared") re-publishes a top-level provider found in
// a dependency whose return type is internal there. The scan hint must not name it.
//
// EXPECTED: no diagnostics.

// MODULE: lib
// FILE: shared/data/Data.kt
package shared.data

import org.koin.core.annotation.Single

internal class Cache

@Single
internal fun provideCache(): Cache = Cache()

// MODULE: app(lib)
// FILE: shared/app/App.kt
package shared.app

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

@Single
class Screen

@Module
@ComponentScan("shared")
class AppModule

@KoinApplication(modules = [AppModule::class])
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, stringLiteral */
