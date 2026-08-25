// FILE: test.kt
// A `module { }` block returned by a function (not a `val`) must be tracked as a first-class
// module: its own `includes(...)` edges recorded, its definitions given a real module id.
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)

fun coreModule() = module {
    single<Repository>()
}

// appModule includes coreModule() — Repository is reachable
fun appModule() = module {
    includes(coreModule())
    single<Service>()
}

fun box(): String {
    val koin = koinApplication {
        modules(appModule())
    }.koin

    val service = koin.get<Service>()
    return if (service.repo != null) "OK" else "FAIL"
}
