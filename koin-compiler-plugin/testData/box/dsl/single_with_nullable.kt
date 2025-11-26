// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class OptionalDependency

class Service(val optional: OptionalDependency? = null)

fun box(): String {
    val m = module {
        // Only register Service, not OptionalDependency
        single<Service>()
    }
    val koin = koinApplication { modules(m) }.koin

    val service = koin.get<Service>()

    // Verify nullable handling - optional should be null since not registered
    return if (service.optional == null) "OK" else "FAIL: nullable not handled correctly"
}
