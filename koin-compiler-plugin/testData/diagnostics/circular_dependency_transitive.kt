// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Transitive cycle: A -> B -> C -> A. KOIN-D004 must surface this even though
// no two definitions form a direct mutual cycle.
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
class B(val c: C)

@Singleton
class C(val a: A)

/* GENERATED_FIR_TAGS: classDeclaration, primaryConstructor, propertyDeclaration */
