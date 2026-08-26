// FILE: test.kt
// includes(extras) where extras is a LOCAL val inside a top-level FUNCTION's body (not inside
// box() itself, which would leave the enclosing module with no id at all — a separate, unrelated
// gap) — traced via IrGetValue -> IrVariable.initializer.
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Repository
class Other
class Service(val repo: Repository, val other: Other)

fun repoModule() = module { single<Repository>() }
val otherModule = module { single<Other>() }

fun appModule(): Module {
    val extras = listOf(repoModule(), otherModule)
    return module {
        includes(extras)
        single<Service>()
    }
}

fun box(): String {
    val koin = koinApplication { modules(appModule()) }.koin
    val service = koin.get<Service>()
    return if (service.repo != null && service.other != null) "OK" else "FAIL"
}
