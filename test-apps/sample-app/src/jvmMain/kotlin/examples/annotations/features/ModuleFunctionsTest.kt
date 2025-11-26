package examples.annotations.features

import org.koin.core.annotation.*
import androidx.lifecycle.ViewModel

// ============================================================================
// Test: @Single/@Factory on functions in @Module class
// ============================================================================

interface ApiService
interface Logger

class ApiServiceImpl : ApiService
class ConsoleLogger : Logger
class FileLogger : Logger
class Presenter(val api: ApiService, val logger: Logger)

@Module
@ComponentScan("examples.annotations.features")
class FunctionsModule {

    @Single
    fun provideApiService(): ApiService = ApiServiceImpl()

    @Factory
    fun providePresenter(api: ApiService, logger: Logger): Presenter = Presenter(api, logger)

    @Single
    @Named("console")
    fun provideConsoleLogger(): Logger = ConsoleLogger()

    @Single
    @Named("file")
    fun provideFileLogger(): Logger = FileLogger()

    @Single(createdAtStart = true)
    fun provideEagerLogger(): Logger = ConsoleLogger()
}

// ============================================================================
// Test: @KoinViewModel on module function
// Note: Requires koin-android dependency for @KoinViewModel annotation
// ============================================================================

class HomeViewModel(val api: ApiService) : ViewModel()
class DetailViewModel(val api: ApiService, val logger: Logger) : ViewModel()

// Uncomment when koin-android is available:
// @Module
// @ComponentScan("examples.annotations.features")
// class ViewModelModule {
//
//     @KoinViewModel
//     fun provideHomeViewModel(api: ApiService): HomeViewModel = HomeViewModel(api)
//
//     @KoinViewModel
//     @Named("detail")
//     fun provideDetailViewModel(api: ApiService, logger: Logger): DetailViewModel = DetailViewModel(api, logger)
// }

// ============================================================================
// Test: Object module (no instance needed)
// ============================================================================

@Module
@ComponentScan("examples.annotations.features")
object SingletonModule {

    @Single
    fun provideGlobalConfig(): String = "global-config"
}
