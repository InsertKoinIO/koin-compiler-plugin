// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class MyService
class GenericService<T>(val service: MyService)

fun box(): String {
    val m = module {
        single<MyService>()
        single<GenericService<String>>()
    }
    val koin = koinApplication { modules(m) }.koin

    val g1 = koin.get<GenericService<String>>()
    val g2 = koin.get<GenericService<String>>()
    val s = koin.get<MyService>()

    return if (g1 === g2 && g1.service === s) {
        "OK"
    } else {
        "FAIL: generic singleton resolution not working"
    }
}
