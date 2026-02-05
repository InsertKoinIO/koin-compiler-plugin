// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class ServiceWithDefault(val name: String = "default_value")

fun box(): String {
    val m = module {
        // Only register ServiceWithDefault, String is not registered
        // Since 'name' has a default value, it should use "default_value"
        single<ServiceWithDefault>()
    }
    val koin = koinApplication { modules(m) }.koin

    val service = koin.get<ServiceWithDefault>()

    // Verify default value is used since String is not registered
    return if (service.name == "default_value") "OK" else "FAIL: expected 'default_value' but got '${service.name}'"
}
