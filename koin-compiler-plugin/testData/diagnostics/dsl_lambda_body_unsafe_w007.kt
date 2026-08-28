// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// A hand-written DSL lambda body (anything other than create(::T)) is opaque by construction —
// the plugin never introspects or regenerates it, so it cannot know what a get<X>() call inside
// actually requires. Missing has no provider here; before KOIN-W007 this class of DSL was fully
// silent (no D001, no disclosure of any kind — see KoinDSLTransformer's "provider-only" fallback
// and issues #36/#49). This test proves it is now at least disclosed, not left fully silent.
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
