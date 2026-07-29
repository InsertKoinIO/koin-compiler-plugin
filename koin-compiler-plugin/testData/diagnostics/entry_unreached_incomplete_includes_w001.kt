// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// An unresolvable `includes(...)` in a module the entry point never loads must cost nothing.
//
// The incomplete-topology signal used to be one boolean for the whole transformer, which walks every
// file in the Gradle module. So a single `includes(makeSomething())` — in a debug-only module, a
// test fixture, anything — switched KOIN-W001 and unreachable-type withholding off for EVERY entry
// point in that compilation, silently. Scoped per owning module instead, it only costs verification
// when the reachability walk actually reaches the module in question.
//
// Here `debugModule` has an unreadable includes() but is never loaded, while `orphanModule` is a
// genuinely unloaded module that SHOULD be reported.
//
// EXPECTED: KOIN-W001 for orphanModule. Its absence would mean one unrelated module's unresolvable
// includes() had disabled reachability for the whole compilation — the coarse-flag bug.
// No KOIN-W003 either: the loaded closure (appModule) is fully resolvable on its own.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Service
class Orphaned
class DebugOnly

fun makeDebugExtras() = module {
    single<DebugOnly>()
}

// Never loaded, and its includes() argument is a function call the walk cannot resolve.
val debugModule = module {
    includes(makeDebugExtras())
}

// Never loaded and never included — this is the one that must still be reported.
val orphanModule = module {
    single<Orphaned>()
}

val appModule = module {
    single<Service>()
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, propertyDeclaration,
   topLevelPropertyDeclaration */
