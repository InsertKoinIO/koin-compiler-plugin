// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

object MyService {
    fun hello() = "hello"
}

fun box(): String {
    val m = module {
        single<MyService>()
    }
    val koin = koinApplication { modules(m) }.koin

    // single<T>() on an `object` resolves to its INSTANCE, not a fresh construction.
    val resolved = koin.get<MyService>()
    if (resolved !== MyService) return "FAIL: single<object>() did not resolve to INSTANCE"
    if (resolved.hello() != "hello") return "FAIL: wrong instance behavior"
    return "OK"
}
