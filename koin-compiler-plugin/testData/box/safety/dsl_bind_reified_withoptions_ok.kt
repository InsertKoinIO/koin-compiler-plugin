// FILE: test.kt
// bind<Interface>() — the reified form used inside withOptions {} — has no KClass value argument
// (the type comes via a type parameter), a distinct shape from the infix `bind(Interface::class)`
// already handled. Previously silently dropped: the binding never attached, so a consumer requiring
// only the interface saw KOIN-D001/D002 on a graph that resolves fine at runtime.
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface ConnService
class RealConn : ConnService

class Consumer(val svc: ConnService)

val appModule = module {
    single<RealConn>() withOptions {
        bind<ConnService>()
    }
    single<Consumer>()
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val consumer = koin.get<Consumer>()
    return if (consumer.svc is RealConn) "OK" else "FAIL"
}
