// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.scope.Scope

// Scope parameter should be injected with the scope receiver itself,
// not resolved via scope.get<Scope>(). Validation should skip it.
@Singleton
class ScopeAwareService(val scope: Scope)

@Module
@ComponentScan
class TestModule

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val service = koin.get<ScopeAwareService>()
    return if (service.scope != null) "OK" else "FAIL"
}
