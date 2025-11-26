// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory

@Module
@ComponentScan
class TestModule

@Factory
class MyFactory

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    // Verify @Factory creates new instances each time
    val f1 = koin.get<MyFactory>()
    val f2 = koin.get<MyFactory>()

    return if (f1 !== f2 && f1 != null && f2 != null) "OK" else "FAIL: @Factory not working"
}
