// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

@Module
@ComponentScan
class TestModule

@Singleton
class HeavyService {
    companion object {
        var instanceCount = 0
    }
    init {
        instanceCount++
    }
}

@Singleton
class Consumer(val lazyHeavy: Lazy<HeavyService>)

fun box(): String {
    HeavyService.instanceCount = 0

    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val consumer = koin.get<Consumer>()

    // HeavyService should not be created yet
    val beforeAccess = HeavyService.instanceCount

    // Access the lazy value
    val heavy = consumer.lazyHeavy.value

    // Now it should be created
    val afterAccess = HeavyService.instanceCount

    // Access again - should be same instance
    val heavy2 = consumer.lazyHeavy.value

    return if (beforeAccess == 0 && afterAccess == 1 && heavy === heavy2) {
        "OK"
    } else {
        "FAIL: Lazy injection not working (before=$beforeAccess, after=$afterAccess)"
    }
}
