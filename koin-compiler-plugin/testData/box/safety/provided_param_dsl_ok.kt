// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.core.annotation.Provided
import org.koin.plugin.module.dsl.single

// @Provided on a constructor parameter — DSL path.
// single<Service>() should compile without error even though ExternalContext
// is not in the DI graph, because the parameter is marked @Provided.
class ExternalContext

class Service(@Provided val ctx: ExternalContext)

val testModule = module {
    single<Service>()
}

fun box(): String {
    val koin = koinApplication {
        modules(
            testModule,
            module { single { ExternalContext() } }
        )
    }.koin

    val service = koin.get<Service>()
    return if (service.ctx != null) "OK" else "FAIL"
}
