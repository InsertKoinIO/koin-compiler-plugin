// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.core.scope.Scope
import org.koin.plugin.module.dsl.single

// Scope parameter in DSL path — should pass the scope receiver itself,
// not resolve via scope.get<Scope>(). Validation should skip it.
class ScopeAwareRepository(val scope: Scope)

val testModule = module {
    single<ScopeAwareRepository>()
}

fun box(): String {
    val koin = koinApplication {
        modules(testModule)
    }.koin

    val repo = koin.get<ScopeAwareRepository>()
    return if (repo.scope != null) "OK" else "FAIL"
}
