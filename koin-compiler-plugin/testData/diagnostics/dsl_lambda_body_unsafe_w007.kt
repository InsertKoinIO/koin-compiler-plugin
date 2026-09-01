// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// A hand-written DSL lambda body (anything other than create(::T)) is opaque by construction —
// the plugin never derives a structured requirement list for it (see KoinDSLTransformer's
// "provider-only" fallback and issues #36/#49), so KOIN-W007 discloses it as unvalidated.
//
// But a `get<X>()` call written INSIDE that lambda is still an ordinary IrCall, and
// GeneratedResolutionCallRegistry only skips calls the PLUGIN generated — a hand-written one is
// tracked like any other Koin resolution call site (by/koinViewModel/etc.), so Missing having no
// provider here is now a hard KOIN-D002, not just disclosed. This is the real-world gap found via
// the app-dsl playground's own AnalyticsModule.kt (`single { StubAnalyticsHelper(get()) } bind
// AnalyticsHelper::class`; see dsl_bind_opaque_lambda_missing_dependency_d002 for that exact
// shape with `bind`) — before this, W007 was the ONLY signal; a genuinely missing dependency
// behind a hand-written get() compiled clean and only crashed at runtime.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Missing
class NeedsMissing(val m: Missing)

val appModule = module {
    single<NeedsMissing> { NeedsMissing(get()) }
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor,
   propertyDeclaration, topLevelPropertyDeclaration */
