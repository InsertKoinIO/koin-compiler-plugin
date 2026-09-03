// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression: Ktor's `install(Koin) { }` was not recognized as a Koin entry point at all — a
// compilation whose only "start" is `install(Koin) { modules(appModule) }` got NO compile-time
// safety validation (generation only), the worst failure class per this project's doctrine: a
// missing dependency here silently compiled clean and would only surface as a runtime crash on the
// first request that needed `Repository`.
//
// `io.ktor.server.application.install` is generic (`fun <P, B, F> P.install(plugin: Plugin<P, B, F>,
// configure: B.() -> Unit)`) and used for EVERY Ktor plugin; recognizing it specifically for Koin's
// plugin requires checking the resolved configuration type argument (B) is koin-ktor's own
// `org.koin.core.KoinKtorApplication` — see KoinStartTransformer.visitCall.
//
// EXPECTED: KOIN-D001 — Service requires Repository, which no module provides.
package testpkg

import io.ktor.server.application.Application
import io.ktor.server.application.install
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)

val appModule = module {
    single<Service>()
}

fun Application.configureKoin() {
    install(Koin) {
        modules(appModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
