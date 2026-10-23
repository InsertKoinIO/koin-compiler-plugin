// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression test for koin-compiler-plugin#16 — KOIN-D007.
//
// `@Factory` returning a fun interface that extends `suspend (P) -> R` would auto-bind to
// `kotlin.coroutines.SuspendFunctionN` via supertype walk. Koin runtime does not support
// suspend function injection yet, so the plugin emits KOIN-D007 and blocks the compile —
// safer than letting silently-broken code ship.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Factory
import org.koin.core.annotation.ComponentScan

fun interface SetUsernameUseCase : suspend (String) -> Unit

class UserRepository {
    suspend fun setUsername(name: String) { /* no-op */ }
}

@Module
@ComponentScan
class AppModule {
    @Factory
    fun provideUserRepository(): UserRepository = UserRepository()

    @Factory
    fun provideSetUsernameUseCase(
        userRepository: UserRepository,
    ): SetUsernameUseCase = SetUsernameUseCase(userRepository::setUsername)
}

fun useIt() {
    val koin = koinApplication { modules(AppModule().module()) }.koin
    koin.get<SetUsernameUseCase>()
}

/* GENERATED_FIR_TAGS: classDeclaration, funInterface, functionDeclaration, lambdaLiteral,
   primaryConstructor, propertyDeclaration */
