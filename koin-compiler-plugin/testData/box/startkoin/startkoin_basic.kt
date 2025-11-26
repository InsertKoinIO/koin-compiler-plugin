// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

@Module
@ComponentScan
class AppModule

@Singleton
class AppService

fun box(): String {
    val koin = startKoin {
        modules(AppModule().module())
    }.koin

    val service = koin.get<AppService>()

    stopKoin()

    return if (service != null) "OK" else "FAIL: startKoin not working"
}
