package examples.explicitmodules

import org.junit.After
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.plugin.module.dsl.startKoin
import kotlin.test.assertNotNull

// Regression for koin#2380: typed `startKoin<T>()` with `@KoinApplication(modules = [AppModule::class])`
// where AppModule is plain `@Module @ComponentScan` (no `@Configuration`) — verify scanned classes
// (including @KoinViewModel) resolve from the entry-point module.
class ExplicitModulesTest {

    @After
    fun tearDown() = stopKoin()

    @Test
    fun appViewModel_resolves_via_explicit_modules() {
        val koin = startKoin<ExplicitMyApp> {
            printLogger(Level.DEBUG)
        }.koin

        val vm = koin.getOrNull<ExplicitAppViewModel>()
        assertNotNull(vm, "ExplicitAppViewModel should be resolved via @KoinApplication(modules = [ExplicitAppModule::class])")

        val repo = koin.getOrNull<DataStoreRepository>()
        assertNotNull(repo, "DataStoreRepository should also be scanned and resolved")
        assert(vm.repo === repo) { "ViewModel should hold the same singleton repo instance" }
    }
}
