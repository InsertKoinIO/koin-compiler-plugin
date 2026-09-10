// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Single

@Module
@ComponentScan
class TestModule

@Single
object MyService {
    fun hello() = "hello"
}

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    // @Single on an `object` resolves to its INSTANCE, not a fresh construction.
    val resolved = koin.get<MyService>()
    if (resolved !== MyService) return "FAIL: @Single object did not resolve to INSTANCE"
    if (resolved.hello() != "hello") return "FAIL: wrong instance behavior"
    return "OK"
}
