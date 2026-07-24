// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE (#2) — DYNAMIC entry-point disclosure on a VALID graph.
//
// The module set is a conditional (`modules(if (flag) AppModule::class else AppModule::class)`), so
// it is not statically resolvable — even though the graph it would assemble is perfectly fine
// (Service's dependency Repo IS provided). The point of KOIN-W003 is that a green build must NOT
// silently imply "verified" for a runtime-decided module set: the plugin discloses that it could not
// verify this entry point at compile time, and there is NO false KOIN-D001 (nothing is actually
// wrong). Falsify-first companion to entry_dynamic_modules_missing (dynamic + a genuine local miss →
// D001 + W003): here the graph is valid, so ONLY the W003 disclosure fires.
// FILE: test.kt
package testpkg

import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

@Singleton
class Repo

@Singleton
class Service(val repo: Repo)

@Module
@ComponentScan
class AppModule

fun main() {
    val flag = true
    startKoin {
        modules(if (flag) AppModule::class else AppModule::class)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, ifExpression, lambdaLiteral, localProperty,
primaryConstructor, propertyDeclaration */
