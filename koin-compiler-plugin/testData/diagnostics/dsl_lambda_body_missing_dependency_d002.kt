// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// A hand-written DSL lambda body (anything other than create(::T)) is opaque by construction —
// the plugin never derives a structured requirement list for it (see KoinDSLTransformer's
// "provider-only" fallback and issues #36/#49). But a `get<X>()` call written INSIDE that lambda
// is still an ordinary IrCall, and GeneratedResolutionCallRegistry only skips calls the PLUGIN
// generated — a hand-written one is tracked like any other Koin resolution call site
// (by/koinViewModel/etc.), so Missing having no provider here is a hard KOIN-D002.
//
// This is the real-world gap found via the app-dsl playground's own AnalyticsModule.kt
// (`single { StubAnalyticsHelper(get()) } bind AnalyticsHelper::class`; see
// dsl_bind_opaque_lambda_missing_dependency_d002 for that exact shape with `bind`). Before this
// fix, this class of DSL was fully silent (no diagnostic of any kind — see issues #36/#49) — a
// genuinely missing dependency behind a hand-written get() compiled clean and only crashed at
// runtime. (KOIN-W007 briefly disclosed this shape as unvalidated without hard-erroring; removed
// once this get()-tracking made the disclosure redundant for the common case — see
// docs/COMPILE_TIME_SAFETY.md for what still isn't covered, namely KOIN-D004 cycle detection
// through an opaque lambda body.)
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
