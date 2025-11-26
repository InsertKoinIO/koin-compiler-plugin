// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

@Module
@ComponentScan
class TestModule

@Singleton
class MySingleton

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    // Verify @Singleton creates a singleton
    val s1 = koin.get<MySingleton>()
    val s2 = koin.get<MySingleton>()

    return if (s1 === s2 && s1 != null) "OK" else "FAIL: @Singleton not working"
}
