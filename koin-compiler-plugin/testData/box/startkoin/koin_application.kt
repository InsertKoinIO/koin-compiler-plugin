// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

@Module
@ComponentScan
class AppModule

@Singleton
class AppService

fun box(): String {
    val koin = koinApplication {
        modules(AppModule().module())
    }.koin

    val service = koin.get<AppService>()

    return if (service != null) "OK" else "FAIL: koinApplication not working"
}
