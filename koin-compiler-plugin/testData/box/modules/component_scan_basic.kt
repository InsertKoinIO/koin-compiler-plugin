// FILE: test.kt
package testpkg

import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Factory

@Module
@ComponentScan("testpkg")
class ScanModule

@Singleton
class ServiceA

@Factory
class ServiceB(val a: ServiceA)

fun box(): String {
    val koin = koinApplication {
        modules(ScanModule().module())
    }.koin

    // Verify @ComponentScan discovers annotated classes in package
    val a = koin.get<ServiceA>()
    val b1 = koin.get<ServiceB>()
    val b2 = koin.get<ServiceB>()

    val singletonWorks = a != null
    val factoryWorks = b1 !== b2
    val dependencyWorks = b1.a === a

    return if (singletonWorks && factoryWorks && dependencyWorks) {
        "OK"
    } else {
        "FAIL: singleton=$singletonWorks, factory=$factoryWorks, dependency=$dependencyWorks"
    }
}
