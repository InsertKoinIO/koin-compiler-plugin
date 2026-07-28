// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// CONTRACT: dependencies arriving via `loadKoinModules(...)` must be declared `@Provided`.
//
// Runtime module loading cannot be verified at compile time, and not because of an implementation
// gap: the call happens at an arbitrary runtime moment, so the plugin cannot prove the module is
// loaded BEFORE a given resolution; the module may never be loaded on some paths; and
// `unloadKoinModules(...)` can remove it again. Rather than invent that knowledge, the plugin
// requires the user to declare intent with `@Provided` — the same mechanism used for platform types
// like Context. Validation steps back and the graph is checked at runtime by `checkModules()`.
//
// This fixture is the documented pattern (docs/COMPILE_TIME_SAFETY.md, "Runtime Module Loading"):
// `Repository` is provided by a module loaded after startKoin, and is marked `@Provided`.
//
// MEASURED: without `@Provided`, this same graph produced THREE diagnostics on code that runs
// correctly — KOIN-D001 (Service's ctor dep), KOIN-D002 (the get<Repository>() call site), and
// KOIN-W001. The two hard errors are correct-and-actionable rather than false positives: at compile
// time Repository genuinely is not in the assembled graph, and `@Provided` is the declared remedy.
// With `@Provided`, both errors clear.
//
// EXPECTED: only KOIN-W001, reporting featureModule as not loaded at startKoin. That warning's
// advice ("add it to modules() or includes()") does not apply to this pattern and is a known rough
// edge — tracked separately. If a KOIN-D001 or KOIN-D002 ever appears here, `@Provided` has stopped
// covering the runtime-loading contract.
package testpkg

import org.koin.core.annotation.Provided
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

@Provided
class Repository
class Service(val repo: Repository)

// Loaded at runtime, not at startKoin.
val featureModule = module {
    single<Repository>()
}

val appModule = module {
    single<Service>()
}

fun main() {
    val koin = startKoin {
        modules(appModule)
    }.koin

    loadKoinModules(featureModule)

    val repo = koin.get<Repository>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty,
   primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
