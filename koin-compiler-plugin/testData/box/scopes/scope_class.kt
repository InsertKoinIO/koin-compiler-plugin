// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Scoped
import org.koin.core.annotation.Scope
import org.koin.core.qualifier.named

@Module
@ComponentScan
class TestModule

class MyScope

@Scoped
@Scope(MyScope::class)
class ScopedService

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    // Use named qualifier for scope creation
    val scope1 = koin.createScope("scope1", named<MyScope>())
    val scope2 = koin.createScope("scope2", named<MyScope>())

    val service1a = scope1.get<ScopedService>()
    val service1b = scope1.get<ScopedService>()
    val service2 = scope2.get<ScopedService>()

    scope1.close()
    scope2.close()

    val sameInScope = service1a === service1b
    val differentAcrossScopes = service1a !== service2

    return if (sameInScope && differentAcrossScopes) "OK" else "FAIL: @Scope not working"
}
