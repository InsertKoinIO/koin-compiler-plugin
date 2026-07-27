// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// A3 — a call site must not resolve against a definition whose module is never loaded.
//
// Constructor-dependency validation (Phase 3.1) resolves against the REACHABLE provider set: it
// walks `modules(...)` at the entry point and follows `includes()`. Call-site validation (Phase 3.5)
// built its universe from every definition DECLARED in the compilation instead, so a `get<T>()` /
// `inject<T>()` resolved happily against a module nobody loads.
//
// The two then contradicted each other in a single compile — KOIN-W001 reporting the module as not
// loaded, and the call site reporting OK a few lines later. Build green, crash at runtime, which is
// the worst failure class for this plugin. Found on playground app-dsl by commenting
// `includes(activityModule)`: `by inject<ActivityTracker>()` in MainActivity kept compiling.
//
// Topology:
//   loadedModule   : single<Other>()    — passed to modules(), reachable
//   unloadedModule : single<Tracker>()  — declared, but nothing loads or includes it
//   call site      : koin.get<Tracker>() — resolvable ONLY via the unloaded module
//
// EXPECTED: KOIN-W001 for unloadedModule AND KOIN-D002 for the get<Tracker>() call site.
// A bare W001 is not enough — a warning does not fail the build, so the crash still ships.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Tracker
class Other

val loadedModule = module { single<Other>() }
val unloadedModule = module { single<Tracker>() }

fun useIt() {
    val koin = koinApplication { modules(loadedModule) }.koin
    val tracker = koin.get<Tracker>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty,
   propertyDeclaration, topLevelPropertyDeclaration */
