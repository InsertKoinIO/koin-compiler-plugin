// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)

val coreModule = module {
    single<Repository>()
}

// appModule includes coreModule — Repository is reachable
val appModule = module {
    includes(coreModule)
    single<Service>()
}

fun box(): String {
    val koin = koinApplication {
        modules(appModule)
    }.koin

    val service = koin.get<Service>()
    return if (service.repo != null) "OK" else "FAIL"
}
