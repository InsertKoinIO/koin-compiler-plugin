// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.parameter.parametersOf

@Module
@ComponentScan
class TestModule

@Factory
class ServiceWithParam(@InjectedParam val id: Int, @InjectedParam val name: String)

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val service1 = koin.get<ServiceWithParam> { parametersOf(42, "test") }
    val service2 = koin.get<ServiceWithParam> { parametersOf(99, "other") }

    val correctParams = service1.id == 42 && service1.name == "test"
    val differentInstances = service1 !== service2
    val differentValues = service2.id == 99 && service2.name == "other"

    return if (correctParams && differentInstances && differentValues) {
        "OK"
    } else {
        "FAIL: @InjectedParam not working"
    }
}
