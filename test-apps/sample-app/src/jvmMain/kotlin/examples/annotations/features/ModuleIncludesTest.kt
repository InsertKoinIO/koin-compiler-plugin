package examples.annotations.features

import org.koin.core.annotation.*

// ============================================================================
// Test: Module includes
// ============================================================================

// Base module with core services
@Module
@ComponentScan("examples.annotations.features.core")
class CoreModule {
    @Single
    fun provideCoreService(): CoreService = CoreService()
}

class CoreService

// Network module
@Module
@ComponentScan("examples.annotations.features.network")
class NetworkModule {
    @Single
    fun provideHttpClient(): HttpClient = HttpClient()
}

class HttpClient

// Database module
@Module
@ComponentScan("examples.annotations.features.database")
class DatabaseModule {
    @Single
    fun provideDatabase(): Database = Database()
}

class Database

// App module that includes others
@Module(includes = [CoreModule::class, NetworkModule::class, DatabaseModule::class])
@ComponentScan("examples.annotations.features")
class AppModule {
    @Single
    fun provideApp(core: CoreService, http: HttpClient, db: Database): App {
        return App(core, http, db)
    }
}

class App(val core: CoreService, val http: HttpClient, val db: Database)

// Single include
@Module(includes = [CoreModule::class])
@ComponentScan("examples.annotations.features")
class SimpleModule
