// FILE: core/Repository.kt
// FLAKY: Occasionally fails when run as part of the full ./test.sh suite (non-deterministic).
// Passes when run alone. Reproduces on baseline, not introduced by current work. Suspected cause:
// Kotlin compiler test framework state pollution within a shared JVM.
package core

import org.koin.core.annotation.Singleton

@Singleton
class Repository

// FILE: service/Service.kt
package service

import core.Repository
import org.koin.core.annotation.Singleton

@Singleton
class Service(val repo: Repository)

// FILE: modules.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration

// CoreModule scans "core" package → provides Repository
@Module
@ComponentScan("core")
@Configuration
class CoreModule

// ServiceModule scans "service" package → provides Service (needs Repository from CoreModule)
// Repository is visible because both modules are unioned into the same koinApplication{} graph
// below (A3, the sole verifier as of 1.1.0) — the shared @Configuration label is not itself what
// makes this resolve, since both modules are passed to modules(...) explicitly either way.
@Module
@ComponentScan("service")
@Configuration
class ServiceModule

// FILE: test.kt
import org.koin.dsl.koinApplication

fun box(): String {
    val koin = koinApplication {
        modules(CoreModule().module(), ServiceModule().module())
    }.koin

    val service = koin.get<service.Service>()
    return if (service.repo != null) "OK" else "FAIL"
}
