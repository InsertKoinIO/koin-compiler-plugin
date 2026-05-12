// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// A -> B -> Lazy<A>. Lazy<T> is the canonical runtime cycle breaker, so this
// must NOT trigger KOIN-D004. (Both bindings are still validated for missing-dependency.)
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
class B(val a: Lazy<A>)

/* GENERATED_FIR_TAGS: classDeclaration, primaryConstructor, propertyDeclaration */
