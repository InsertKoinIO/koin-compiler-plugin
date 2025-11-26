// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Configuration

@Module
@ComponentScan
@Configuration
class FeatureModule

@Singleton
class FeatureService

fun box(): String {
    // @Configuration modules can be discovered automatically
    // For now, test that @Configuration annotation is accepted
    val koin = koinApplication {
        modules(FeatureModule().module())
    }.koin

    val service = koin.get<FeatureService>()

    return if (service != null) "OK" else "FAIL: @Configuration module not working"
}
