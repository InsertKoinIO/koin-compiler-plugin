package examples.explicitmodules

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

// Repro for koin#2380:
//   @Module @ComponentScan AppModule (NO @Configuration on the listed module)
//   @KoinApplication(modules = [AppModule::class]) class MyApp (class, not object)
//   @KoinViewModel class AppViewModel(deps)  in the scanned package
// Reporter (hmy65) saw the equivalent setup fail with
// "Missing definition: AppViewModel" at the koinViewModel<>() call site
// on koin-plugin 1.0.0-RC2 / koin 4.2.0.

@Module
@ComponentScan
class ExplicitAppModule

@KoinApplication(modules = [ExplicitAppModule::class])
class ExplicitMyApp

@Singleton
class DataStoreRepository

@KoinViewModel
class ExplicitAppViewModel(val repo: DataStoreRepository)
