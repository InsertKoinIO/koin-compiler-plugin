// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.scoped
import org.koin.core.qualifier.named

class Repository

class ScopedService(val repository: Repository)

fun box(): String {
    val m = module {
        single<Repository>()
        scope(named("myScope")) {
            scoped<ScopedService>()
        }
    }
    val koin = koinApplication { modules(m) }.koin

    val repo = koin.get<Repository>()

    // Create first scope and get scoped instance
    val scope1 = koin.createScope("scope1", named("myScope"))
    val service1a = scope1.get<ScopedService>()
    val service1b = scope1.get<ScopedService>()

    // Create second scope and get scoped instance
    val scope2 = koin.createScope("scope2", named("myScope"))
    val service2 = scope2.get<ScopedService>()

    scope1.close()
    scope2.close()

    // Verify scoped behavior:
    // - Same instance within same scope
    // - Different instances across different scopes
    // - Dependencies injected correctly
    val sameWithinScope = service1a === service1b
    val differentAcrossScopes = service1a !== service2
    val dependencyInjected = service1a.repository === repo

    return if (sameWithinScope && differentAcrossScopes && dependencyInjected) {
        "OK"
    } else {
        "FAIL: sameWithinScope=$sameWithinScope, differentAcrossScopes=$differentAcrossScopes, dependencyInjected=$dependencyInjected"
    }
}
