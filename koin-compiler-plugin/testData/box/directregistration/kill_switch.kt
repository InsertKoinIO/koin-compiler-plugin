// DISABLE_DIRECT_REGISTRATION
// FILE: test.kt
// KTZ-4829: `koinCompiler { directRegistration = false }` must restore the pre-1.3.0 generated
// shape (buildSingle/bind DSL calls). The .fir.ir.txt golden is the assertion: it must contain
// `buildSingle` and `bind` calls and no `saveMapping`.
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

@Module
@ComponentScan
class TestModule

interface Api

@Singleton
class Impl : Api

fun box(): String {
    val koin = koinApplication { modules(TestModule().module()) }.koin
    return if (koin.get<Api>() === koin.get<Impl>()) "OK" else "FAIL"
}
