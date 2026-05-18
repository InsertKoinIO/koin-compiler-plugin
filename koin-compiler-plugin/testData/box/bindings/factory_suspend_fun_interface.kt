// FILE: test.kt
// Regression test for KTZ-4041 / GH koin-compiler-plugin#16:
// "Compiler crash when @Factory returns fun interface extending suspend function type".
//
// Trigger: a fun interface that extends `suspend (P) -> R`. The plugin walks the
// return type's supertypes to find binding targets and emits a `bindingN`
// value-parameter in a hint function. Before the fix the parameter type was
// constructed with `emptyArray()` for type args, producing a raw
// `SuspendFunction1` — which crashes JVM IR codegen at
// AbstractTypeMapper.mapSuspendFunctionType with
// `IndexOutOfBoundsException: Empty list doesn't contain element at index -1`
// (the suspend-to-continuation lowering reads `arguments[size - 1]`).
//
// Fix: classLikeTypeWithDefaultArgs() in KoinModuleFirGenerator now fills type
// parameters with `Any?` when constructing hint param types, mirroring
// IrClass.hintParameterType from cc9ee22 (issue #18, native generics).
//
// This test only verifies that compilation completes and the factory resolves
// to a non-null instance — suspend function INVOCATION through Koin isn't
// supported yet (separate runtime work).
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

fun box(): String {
    val koin = koinApplication { modules(AppModule().module()) }.koin
    val useCase = koin.get<SetUsernameUseCase>()
    val repo = koin.get<UserRepository>()
    return if (useCase != null && repo != null) "OK" else "FAIL"
}
