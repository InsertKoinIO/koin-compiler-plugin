// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

// Delegation pattern: class implements interface and injects same interface.
// With binds = [], auto-binding is suppressed — no recursive resolution.
interface MyService {
    fun doWork(): String
}

@Singleton
class RealService : MyService {
    override fun doWork() = "real"
}

@Singleton(binds = [])
class DecoratedService(val delegate: MyService) : MyService {
    override fun doWork() = "decorated:${delegate.doWork()}"
}

@Module
@ComponentScan
class TestModule

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    // RealService is auto-bound to MyService
    // DecoratedService has binds = [] so it's NOT bound to MyService
    // Resolving MyService should give RealService (no cycle)
    val service = koin.get<MyService>()
    val decorated = koin.get<DecoratedService>()
    return if (service.doWork() == "real" && decorated.doWork() == "decorated:real") "OK" else "FAIL"
}
