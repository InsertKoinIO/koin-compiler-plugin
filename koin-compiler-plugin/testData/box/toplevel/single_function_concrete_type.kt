// FILE: test.kt
package testpkg

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.koinApplication

class JsonLike(val ignoreUnknownKeys: Boolean)

@Single
fun json(): JsonLike = JsonLike(ignoreUnknownKeys = true)

@Module
@ComponentScan("testpkg")
class TestModule

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val json1 = koin.get<JsonLike>()
    val json2 = koin.get<JsonLike>()

    return if (json1 === json2 && json1.ignoreUnknownKeys) {
        "OK"
    } else {
        "FAIL: top-level @Single function was not registered as singleton"
    }
}
