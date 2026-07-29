// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — baseline matrix. Entry point = REAL koin-core `startKoin { modules(K::class) }`
// (org.koin.core.context.startKoin), loading an ANNOTATION @ComponentScan module.
//
// Service (scanned @Singleton) needs Repo; Repo is a plain class, provided by no definition.
// This is a GENUINE miss on the everyday DSL-style entry point.
//
// TARGET: KOIN-D001 Missing dependency: Repo, attributed to the startKoin root.
// PROBE: real koin-core startKoin only flips the entry-point flag (KoinStartTransformer.kt:126-131)
// — the authoritative validateFullGraph path is gated on the plugin-stub startKoin, not koin-core's.
// This records whether a scanned annotation module loaded via real startKoin is verified at all,
// or silently skipped (a false negative — the worst class per doctrine).
// FILE: test.kt
package testpkg

import org.koin.core.context.startKoin
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

fun main() {
    startKoin {
        modules(AppModule::class)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
