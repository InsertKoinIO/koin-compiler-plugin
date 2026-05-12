// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Direct mutual cycle: A's constructor takes B, B's constructor takes A.
// Plugin must emit KOIN-D004 once (canonicalized) — at runtime this stack-overflows
// because each get<A>() triggers get<B>() and vice versa.
package testpkg

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

@Module
@ComponentScan("testpkg")
class TestModule

@Singleton
class A(val b: B)

@Singleton
class B(val a: A)

/* GENERATED_FIR_TAGS: classDeclaration, primaryConstructor, propertyDeclaration */
