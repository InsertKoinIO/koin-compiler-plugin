// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

// Service depends on Config — Config is provided via create(::provideConfig) (function reference)
class Config(val name: String)
class Service(val config: Config)

fun provideConfig(): Config = Config("test")

val appModule = module {
    single { create(::provideConfig) }
    single<Service>()
}

fun box(): String {
    val koin = koinApplication {
        modules(appModule)
    }.koin

    val service = koin.get<Service>()
    return if (service.config.name == "test") "OK" else "FAIL"
}
