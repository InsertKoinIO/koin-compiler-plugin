// FILE: test.kt
// includes(a + b) — list concatenation, both operands resolvable.
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Repository
class Other
class Service(val repo: Repository, val other: Other)

fun repoModule() = module { single<Repository>() }
val otherModule = module { single<Other>() }

val leftModules = listOf(repoModule())
val rightModules = listOf(otherModule)

val appModule = module {
    includes(leftModules + rightModules)
    single<Service>()
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val service = koin.get<Service>()
    return if (service.repo != null && service.other != null) "OK" else "FAIL"
}
