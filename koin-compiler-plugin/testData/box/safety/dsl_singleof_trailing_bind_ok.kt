// FILE: test.kt
// singleOf(::Ctor) { bind<Interface>() } — the real production shape (kotzilla.io/server's
// ValkeyModule.kt): the trailing options block's bind<T>() must attach to singleOf's own DslDef.
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

interface ConnService
class RealConn : ConnService

class Consumer(val svc: ConnService)

val appModule = module {
    singleOf(::RealConn) { bind<ConnService>() }
    singleOf(::Consumer)
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val consumer = koin.get<Consumer>()
    return if (consumer.svc is RealConn) "OK" else "FAIL"
}
