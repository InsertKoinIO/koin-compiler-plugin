// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

@Module
@ComponentScan
class ModuleA

@Module
@ComponentScan
class ModuleB

@Singleton
class ServiceA

@Singleton
class ServiceB(val a: ServiceA)

fun box(): String {
    val koin = koinApplication {
        modules(ModuleA().module(), ModuleB().module())
    }.koin

    val serviceA = koin.get<ServiceA>()
    val serviceB = koin.get<ServiceB>()

    val bothExist = serviceA != null && serviceB != null
    val dependencyInjected = serviceB.a === serviceA

    return if (bothExist && dependencyInjected) "OK" else "FAIL: multiple modules not working"
}
