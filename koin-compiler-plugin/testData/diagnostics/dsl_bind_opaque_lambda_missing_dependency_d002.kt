// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Real-world gap found via the app-dsl playground's own AnalyticsModule.kt:
//   single { StubAnalyticsHelper(get()) } bind AnalyticsHelper::class
// where StubAnalyticsHelper's constructor param (NewStuff, here renamed Missing) has NO provider
// anywhere. `bind` doesn't change anything about requirement derivation — the lambda body is
// still opaque (KOIN-W007 fires, same as dsl_lambda_body_unsafe_w007), but the hand-written
// `get<Missing>()` call inside it is an ordinary IrCall the plugin never generated, so
// GeneratedResolutionCallRegistry doesn't skip it: it's tracked as a real call site like any
// other, and Missing having no provider is a hard KOIN-D002.
//
// Before this fix, this exact shape compiled clean (only W007 disclosed it as unvalidated) and
// crashed at runtime with NoDefinitionFoundException the first time StubAnalyticsHelper was
// resolved — the worst failure class per this project's doctrine (silent > broken).
package testpkg

import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface AnalyticsHelper
class Missing
class StubAnalyticsHelper(val m: Missing) : AnalyticsHelper

val appModule = module {
    single { StubAnalyticsHelper(get()) } bind AnalyticsHelper::class
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration,
   lambdaLiteral, primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
