// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

@Module
@ComponentScan
class TestModule

interface Repository

@Singleton
class RepositoryImpl : Repository

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    // Should be able to get by implementation type
    val impl = koin.get<RepositoryImpl>()

    // Should also be able to get by interface type (auto-binding)
    val repo = koin.get<Repository>()

    val sameInstance = impl === repo
    val correctType = repo is RepositoryImpl

    return if (sameInstance && correctType) "OK" else "FAIL: auto interface binding not working"
}
