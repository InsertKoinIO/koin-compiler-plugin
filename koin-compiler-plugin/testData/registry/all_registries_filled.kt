// FILE: test.kt
// Input for KoinIrExtensionRegistryReleaseTest (not a generated box test): one compilation that fills
// every per-compilation IR registry — @PropertyValue (PropertyValueRegistry), @Provided
// (ProvidedTypeRegistry) and plugin-generated get()/getProperty() calls (GeneratedResolutionCallRegistry).
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Property
import org.koin.core.annotation.PropertyValue
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Singleton
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@PropertyValue("api.timeout")
val defaultApiTimeout = 30

@Provided
class ExternalContext

@Singleton
class Service(val ctx: ExternalContext)

@Factory
class ApiClient(@Property("api.timeout") val timeout: Int, val service: Service)

@Module
@ComponentScan
class TestModule

fun box(): String {
    val koin = koinApplication {
        modules(
            TestModule().module(),
            module { single { ExternalContext() } }
        )
    }.koin

    return if (koin.get<ApiClient>().timeout == 30) "OK" else "FAIL"
}
