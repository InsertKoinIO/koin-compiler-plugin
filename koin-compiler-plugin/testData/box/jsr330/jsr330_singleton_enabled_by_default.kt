// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

// Baseline: jakarta.inject.Singleton is processed like @Singleton when jsr330 is left at its
// default (true). Establishes the RED case for jsr330_disabled_skips_processing.kt: without the
// jsr330 gate, this class is always registered — with it, it must stay registered by default.
@Module
@ComponentScan
class TestModule

@jakarta.inject.Singleton
class Service

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val service = koin.getOrNull<Service>()

    return if (service != null) "OK" else "FAIL: jakarta.inject.Singleton was not processed by default"
}
