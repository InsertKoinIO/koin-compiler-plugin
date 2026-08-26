// FILE: test.kt
// includes(arrayOf(...).toList()) — .toList() resolves only as far as its receiver does; here the
// receiver is a freshly-built arrayOf(...), so it fully resolves.
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Repository
class Other
class Service(val repo: Repository, val other: Other)

fun repoModule() = module { single<Repository>() }
val otherModule = module { single<Other>() }

val appModule = module {
    includes(arrayOf(repoModule(), otherModule).toList())
    single<Service>()
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val service = koin.get<Service>()
    return if (service.repo != null && service.other != null) "OK" else "FAIL"
}
