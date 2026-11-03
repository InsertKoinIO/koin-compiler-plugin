// RUN_PIPELINE_TILL: BACKEND
// Foo (module a) takes a type from a's implementation dependency, which b can't see. b's
// OWN DSL hint encodes Foo's requirements: the off-classpath type must not be named in b's IR.
//
// EXPECTED: no diagnostics (no entry point).

// MODULE: aprime
// FILE: aprime/Config.kt
package aprime

class Config

// MODULE: a(aprime)
// FILE: a/Foo.kt
package a

class Foo(val config: aprime.Config)

// MODULE: b(a)
// FILE: b/B.kt
package b

import a.Foo
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

val bModule = module { single<Foo>() }

/* GENERATED_FIR_TAGS: classDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
