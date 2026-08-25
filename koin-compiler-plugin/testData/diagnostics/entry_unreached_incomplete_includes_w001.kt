// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// An unresolvable `includes(...)` in a module the entry point never loads must cost nothing.
//
// The incomplete-topology signal used to be one boolean for the whole transformer, which walks every
// file in the Gradle module. So a single `includes(...)` with an unreadable argument — in a
// debug-only module, a test fixture, anything — switched KOIN-W001 and unreachable-type withholding
// off for EVERY entry point in that compilation, silently. Scoped per owning module instead, it only
// costs verification when the reachability walk actually reaches the module in question.
//
// Here `debugModule` has an unreadable includes() but is never loaded; `debugExtras` (reached only
// through that unreadable argument) and `orphanModule` (never included anywhere) are both genuinely
// unloaded modules that SHOULD be reported. The includes() argument is a `List<Module>` variable, not
// a function call — a function-returned module now resolves properly (see dsl_function_module_*
// tests), so this fixture uses the construct that's still genuinely unresolvable (deferred DSL
// limitation, see docs/COMPILE_SAFETY_A3_PLAN.md) to keep exercising the per-module scoping this test
// actually pins.
//
// EXPECTED: KOIN-W001 for BOTH debugExtras and orphanModule. Either one's absence would mean one
// unrelated module's unresolvable includes() had disabled reachability for the whole compilation —
// the coarse-flag bug. No KOIN-W003 either: the loaded closure (appModule) is fully resolvable on
// its own.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Service
class Orphaned
class DebugOnly

val debugExtras = module {
    single<DebugOnly>()
}

val debugExtrasList = listOf(debugExtras)

// Never loaded, and its includes() argument is a List<Module> the walk cannot resolve.
val debugModule = module {
    includes(debugExtrasList)
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
