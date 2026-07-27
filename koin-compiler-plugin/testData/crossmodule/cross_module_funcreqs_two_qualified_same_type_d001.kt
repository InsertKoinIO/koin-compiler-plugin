// RUN_PIPELINE_TILL: BACKEND
// A3 Gate-2 — the funcreqs carrier must be keyed by (return type, qualifier), not return type alone.
//
// Two qualified providers of the SAME type is ordinary Koin (`@Named("auth")` vs `@Named("plain")`
// HttpClient). But the carrier that ships a cross-module function provider's REQUIREMENTS keys only
// on the return type, in two places:
//   - the compilation-wide emit-once set, so the second provider's hint is never written;
//   - the hint function name `funcreqs_<flat-return-fqn>`, so both providers would collide anyway,
//     and decode picks whichever symbol comes back first.
//
// Consequence at the consumer: both ExternalFunctionDefs survive dedupe — that key IS
// (returnType, qualifier) — but both read the single surviving carrier. One provider's dependencies
// are never validated (a silent false negative, the exact hole Gate-2 exists to close) and the other
// provider's dependencies are falsely attributed to it.
//
// NEITHER dependency is provided here, deliberately: if the carrier were correct both must be
// reported, so the test does not depend on which provider wins the emit-once race. Seeing exactly
// ONE of the two is the signature of the bug.
//
// EXPECTED: KOIN-D001 for lib.AuthInterceptor AND for lib.PlainConfig at the root.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

class AuthInterceptor
class PlainConfig
class HttpClient(val tag: String)

@Single
@Named("auth")
fun authClient(interceptor: AuthInterceptor): HttpClient = HttpClient("auth")

@Single
@Named("plain")
fun plainClient(config: PlainConfig): HttpClient = HttpClient("plain")

@Module
@ComponentScan("lib")
class LibModule

// MODULE: app(lib)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin
import lib.LibModule

@KoinApplication(modules = [LibModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
