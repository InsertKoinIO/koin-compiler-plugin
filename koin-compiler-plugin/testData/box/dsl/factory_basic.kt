// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.factory

class MyService

fun box(): String {
    val m = module {
        factory<MyService>()
    }
    val koin = koinApplication { modules(m) }.koin

    // Verify factory behavior - different instances returned
    val s1 = koin.get<MyService>()
    val s2 = koin.get<MyService>()

    return if (s1 !== s2 && s1 != null && s2 != null) "OK" else "FAIL: factory behavior not working"
}
