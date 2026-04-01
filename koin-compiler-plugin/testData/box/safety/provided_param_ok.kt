// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Provided

// @Provided on a constructor PARAMETER — the parameter's type is externally available.
// Safety validation should skip it (no "Missing dependency" error).
class ExternalContext

@Singleton
class Service(@Provided val ctx: ExternalContext)

@Module
@ComponentScan
class TestModule

fun box(): String {
    val koin = koinApplication {
        modules(
            TestModule().module(),
            org.koin.dsl.module { single { ExternalContext() } }
        )
    }.koin

    val service = koin.get<Service>()
    return if (service.ctx != null) "OK" else "FAIL"
}
