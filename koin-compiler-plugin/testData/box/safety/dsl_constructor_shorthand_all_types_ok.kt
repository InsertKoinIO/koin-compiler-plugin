// FILE: test.kt
// factoryOf/scopedOf — same code path as singleOf, different DefinitionType. Confirms the
// fqName->DefinitionType mapping is wired correctly for more than just singleOf. (viewModelOf
// shares the exact same code path but needs androidx.lifecycle.ViewModel, unavailable in this
// plain-JVM test harness — not exercised here, same as every other viewModel-related shape in
// this test suite.)
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class Repository
class FactoryMade(val repo: Repository)
class ScopedMade(val repo: Repository)

val appModule = module {
    singleOf(::Repository)
    factoryOf(::FactoryMade)
    scope(named("myScope")) {
        scopedOf(::ScopedMade)
    }
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val f1 = koin.get<FactoryMade>()
    val f2 = koin.get<FactoryMade>()
    val factoryWorks = f1 !== f2 && f1.repo != null

    val scopeInstance = koin.createScope("s1", named("myScope"))
    val scoped = scopeInstance.get<ScopedMade>()
    scopeInstance.close()

    return if (factoryWorks && scoped.repo != null) "OK" else "FAIL: factory=$factoryWorks"
}
