// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Named
import org.koin.core.qualifier.named

@Module
@ComponentScan
class TestModule

interface Service

@Singleton
@Named("prod")
class ProdService : Service

@Singleton
@Named("test")
class TestService : Service

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val prod = koin.get<Service>(named("prod"))
    val test = koin.get<Service>(named("test"))

    return if (prod is ProdService && test is TestService) "OK" else "FAIL: @Named on class not working"
}
