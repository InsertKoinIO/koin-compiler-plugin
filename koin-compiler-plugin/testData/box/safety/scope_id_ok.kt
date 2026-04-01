// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.ScopeId

// @ScopeId(name = "scopeName") resolves a dependency from a named Koin scope.
// Compile safety should skip validation for @ScopeId parameters.
class UserSession(val name: String)

@Factory
class ProfileService(@ScopeId(name = "user_session") val session: UserSession)

@Module
@ComponentScan
class TestModule

fun box(): String {
    // Just verify the module compiles — @ScopeId params are skipped in validation
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin
    return "OK"
}
