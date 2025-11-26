// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Repository

class Service(val repository: Repository)

fun box(): String {
    val m = module {
        single<Repository>()
        single<Service>()
    }
    val koin = koinApplication { modules(m) }.koin

    val repo = koin.get<Repository>()
    val service = koin.get<Service>()

    // Verify dependency injection - same repository instance
    return if (service.repository === repo) "OK" else "FAIL: dependency not injected correctly"
}
