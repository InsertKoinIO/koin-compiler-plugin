// FILE: test.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.koinApplication

@Module
class TestModule {
    @Single(binds = [Repository::class])
    fun provideRepository(): RepositoryImpl = RepositoryImpl()
}

interface Repository

class RepositoryImpl : Repository

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val impl = koin.get<RepositoryImpl>()
    val repo = koin.get<Repository>()

    val sameInstance = impl === repo
    val correctType = repo is RepositoryImpl

    return if (sameInstance && correctType) "OK" else "FAIL: provider function binding not working"
}
