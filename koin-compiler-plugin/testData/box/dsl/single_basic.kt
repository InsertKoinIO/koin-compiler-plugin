// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class MyService

fun box(): String {
    val m = module {
        single<MyService>()
    }
    val koin = koinApplication { modules(m) }.koin

    // Verify singleton behavior - same instance returned
    val s1 = koin.get<MyService>()
    val s2 = koin.get<MyService>()

    return if (s1 === s2 && s1 != null) "OK" else "FAIL: singleton behavior not working"
}
