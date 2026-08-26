// FILE: test.kt
// singleOf(::Ctor).bind<Interface>() — bind<T>() chained directly onto singleOf's result, a
// distinct IR shape from the trailing-options-block form (bind's receiver IS the singleOf call,
// rather than an implicit lambda receiver).
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module

interface ConnService
class RealConn : ConnService

class Consumer(val svc: ConnService)

val appModule = module {
    singleOf(::RealConn).bind<ConnService>()
    singleOf(::Consumer)
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val consumer = koin.get<Consumer>()
    return if (consumer.svc is RealConn) "OK" else "FAIL"
}
