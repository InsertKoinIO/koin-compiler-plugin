// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Reproducer for predicate-dep check: @KoinViewModel is present but the
// `koin-core-viewmodel` artifact (which provides Module.buildViewModel)
// is not on the classpath. Plugin must emit a compile error naming the
// missing artifact, instead of silently skipping the definition.
package testpkg

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinViewModel

@Module
@ComponentScan("testpkg")
class AppModule

@KoinViewModel
class MyViewModel

/* GENERATED_FIR_TAGS: classDeclaration, primaryConstructor */
