// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — baseline matrix. Entry point with a DYNAMIC module set: the modules(...) argument
// is a conditional expression, not a static KClass reference, so the loaded set is NOT statically
// resolvable at compile time.
//
// Service (scanned @Singleton) needs Repo; Repo is provided by no definition. Genuine miss —
// but the verifier cannot know the module set.
//
// RESULT (#2 implemented): a VISIBLE KOIN-W003 "graph not verifiable at compile time" disclosure —
// never a silent pass. The lambda walker (collectModuleClassesFromLambda) flags the conditional
// modules(...) argument as non-static → the entry point is classified DYNAMIC → discloseDynamicEntryPoint
// emits KOIN-W003. The KOIN-D001 here is the separate local miss A2 catches (Repo genuinely absent in
// AppModule, which IS statically visible); it is unrelated to the dynamic-ness. See
// entry_dynamic_valid_disclosed_w003 for the valid-graph case where ONLY W003 fires.
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
    val useProd = true
    startKoin {
        modules(if (useProd) AppModule::class else AppModule::class)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, ifExpression, lambdaLiteral, localProperty, primaryConstructor, propertyDeclaration */
