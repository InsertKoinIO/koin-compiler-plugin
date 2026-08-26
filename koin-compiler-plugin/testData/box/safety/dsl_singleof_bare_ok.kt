// FILE: test.kt
// singleOf(::Ctor) with no bindings/options at all — should behave like single<Ctor>().
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class Repository
class Consumer(val repo: Repository)

val appModule = module {
    singleOf(::Repository)
    singleOf(::Consumer)
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val consumer = koin.get<Consumer>()
    return if (consumer.repo != null) "OK" else "FAIL"
}
