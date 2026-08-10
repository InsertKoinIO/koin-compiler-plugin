// JSR330_DISABLED
// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

// With jsr330 = false, jakarta.inject.Singleton must NOT be recognized as a Koin definition
// annotation — the class is silently skipped, not registered under any type.
@Module
@ComponentScan
class TestModule

@jakarta.inject.Singleton
class Service

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val service = koin.getOrNull<Service>()

    return if (service == null) "OK" else "FAIL: jakarta.inject.Singleton was processed despite jsr330 = false"
}
