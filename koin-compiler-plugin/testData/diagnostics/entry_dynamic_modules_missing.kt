// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — baseline matrix. Entry point with a DYNAMIC module set: the modules(...) argument
// is a conditional expression, not a static KClass reference, so the loaded set is NOT statically
// resolvable at compile time.
//
// Service (scanned @Singleton) needs Repo; Repo is provided by no definition. Genuine miss —
// but the verifier cannot know the module set.
//
// TARGET (Step 6): a VISIBLE "graph unverified" warning — never a silent pass. Compile-time safety
// cannot verify an unknowable module set, and silence here is the doctrine's worst failure class.
// PROBE: the lambda walker collects only IrClassReference args (collectModuleClassesFromLambda);
// a conditional yields no static class, so the set is empty and validateFullGraph is skipped.
// Records whether the shipping code discloses this or silently emits nothing.
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
