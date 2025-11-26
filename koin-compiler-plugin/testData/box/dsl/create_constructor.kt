// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.scoped
import org.koin.plugin.module.dsl.create
import org.koin.core.qualifier.named

class Repository

class Service(val repository: Repository)

fun box(): String {
    val m = module {
        single<Repository>()
        scope(named("myScope")) {
            scoped { create(::Service) }
        }
    }
    val koin = koinApplication { modules(m) }.koin

    val repo = koin.get<Repository>()
    val scope = koin.createScope("test", named("myScope"))
    val service = scope.get<Service>()

    scope.close()

    // Verify create(::T) injects dependencies correctly
    return if (service.repository === repo) "OK" else "FAIL: create() did not inject dependency"
}
