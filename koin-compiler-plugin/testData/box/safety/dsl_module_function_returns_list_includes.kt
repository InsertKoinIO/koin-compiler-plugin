// FILE: test.kt
// includes(testDataModules()) where the function's whole body is `= listOf(...)` — follows the
// single-statement body, matching a real production shape.
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Repository
class Other
class Service(val repo: Repository, val other: Other)

fun repoModule() = module { single<Repository>() }
val otherModule = module { single<Other>() }

fun testDataModules(): List<org.koin.core.module.Module> = listOf(repoModule(), otherModule)

val appModule = module {
    includes(testDataModules())
    single<Service>()
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val service = koin.get<Service>()
    return if (service.repo != null && service.other != null) "OK" else "FAIL"
}
