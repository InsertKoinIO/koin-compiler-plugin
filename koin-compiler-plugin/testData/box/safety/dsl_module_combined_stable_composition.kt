// FILE: test.kt
// Combined shape mirroring the real production pattern (shared/test-app/TestDataModule.kt +
// TestApp.kt): a function returning a listOf(...), concatenated with a local val, converted from an
// array — every piece stable (no branching). appModule is a top-level function (real id), not
// declared inside box() itself, so the composition is actually attributed and exercised.
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Repository
class Other
class Third
class Service(val repo: Repository, val other: Other, val third: Third)

fun repoModule() = module { single<Repository>() }
val otherModule = module { single<Other>() }
fun thirdModule() = module { single<Third>() }

fun coreModules(): List<Module> = listOf(repoModule(), otherModule)

fun appModule(): Module {
    val extra = arrayOf(thirdModule()).toList()
    return module {
        includes(coreModules() + extra)
        single<Service>()
    }
}

fun box(): String {
    val koin = koinApplication { modules(appModule()) }.koin
    val service = koin.get<Service>()
    return if (service.repo != null && service.other != null && service.third != null) "OK" else "FAIL"
}
