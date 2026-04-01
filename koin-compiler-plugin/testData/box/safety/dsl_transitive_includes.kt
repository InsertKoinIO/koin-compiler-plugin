// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Database
class Repository(val db: Database)
class Service(val repo: Repository)

// Transitive includes: appModule -> dataModule -> dbModule
val dbModule = module {
    single<Database>()
}

val dataModule = module {
    includes(dbModule)
    single<Repository>()
}

val appModule = module {
    includes(dataModule)
    single<Service>()
}

fun box(): String {
    val koin = koinApplication {
        modules(appModule)
    }.koin

    val service = koin.get<Service>()
    return if (service.repo.db != null) "OK" else "FAIL"
}
