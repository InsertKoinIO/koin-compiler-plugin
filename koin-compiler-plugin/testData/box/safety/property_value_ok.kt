// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Property
import org.koin.core.annotation.PropertyValue

// @PropertyValue provides a compile-time default for @Property("key").
// Compile safety should NOT warn because @PropertyValue("api.timeout") exists.
@PropertyValue("api.timeout")
val defaultApiTimeout = 30

@Factory
class ApiClient(@Property("api.timeout") val timeout: Int)

@Module
@ComponentScan
class TestModule

fun box(): String {
    // Verify the module compiles — @Property/@PropertyValue matching is validated at compile time
    val app = koinApplication {
        modules(TestModule().module())
    }
    return "OK"
}
