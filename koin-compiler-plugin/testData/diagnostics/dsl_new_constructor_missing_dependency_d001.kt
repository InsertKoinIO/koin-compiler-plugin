// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Koin's OWN `new(::Ctor)` (org.koin.core.module.dsl) — the constructor-reference DSL Koin itself
// ships, functionally identical to this plugin's `create(::T)` but a real Koin runtime function this
// plugin does not need to rewrite. Previously not recognized at all: `single<T> { new(::T) }` fell
// into the generic "opaque lambda body" fallback (providerOnly = true, empty requirements) — T's own
// constructor dependencies went completely unvalidated. Sibling bug to create(::function): a
// definition's real dependencies silently treated as none, the worst failure class (compiles green,
// throws NoDefinitionFoundException at runtime).
//
// EXPECTED: KOIN-D001 for MissingDep — proves new(::Ctor)'s referenced constructor is now validated.
package testpkg

import org.koin.core.module.dsl.new
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class MissingDep
class Repo(val dep: MissingDep)

val appModule = module {
    single { new(::Repo) }
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, functionDeclaration, lambdaLiteral,
   primaryConstructor, propertyDeclaration */
