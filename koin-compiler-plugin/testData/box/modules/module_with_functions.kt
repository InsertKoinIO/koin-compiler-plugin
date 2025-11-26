// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Factory

interface Repository
class RepositoryImpl : Repository

interface Service
class ServiceImpl(val repo: Repository) : Service

@Module
@ComponentScan
class FunctionModule {
    @Singleton
    fun provideRepository(): Repository = RepositoryImpl()

    @Factory
    fun provideService(repo: Repository): Service = ServiceImpl(repo)
}

fun box(): String {
    val koin = koinApplication {
        modules(FunctionModule().module())
    }.koin

    // Verify @Singleton/@Factory functions in @Module work
    val repo1 = koin.get<Repository>()
    val repo2 = koin.get<Repository>()
    val service1 = koin.get<Service>()
    val service2 = koin.get<Service>()

    val singletonWorks = repo1 === repo2
    val factoryWorks = service1 !== service2
    val dependencyWorks = (service1 as ServiceImpl).repo === repo1

    return if (singletonWorks && factoryWorks && dependencyWorks) {
        "OK"
    } else {
        "FAIL: singleton=$singletonWorks, factory=$factoryWorks, dependency=$dependencyWorks"
    }
}
