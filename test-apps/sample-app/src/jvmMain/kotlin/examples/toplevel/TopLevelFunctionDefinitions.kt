package examples.toplevel

import org.koin.core.annotation.*

// ============================================================================
// Test: @Single/@Factory on top-level functions (scanned by @ComponentScan)
// ============================================================================

// Service interfaces and implementations for testing top-level functions
interface TLDatabaseService
interface TLCacheService
interface TLNetworkClient

class TLPostgresDatabase : TLDatabaseService
class TLRedisCache : TLCacheService
class TLOkHttpClient : TLNetworkClient
class TLServiceFacade(val db: TLDatabaseService, val cache: TLCacheService)

// Top-level function definitions - these should be picked up by @ComponentScan
@Singleton
fun provideTLDatabase(): TLDatabaseService = TLPostgresDatabase()

@Factory
fun provideTLCache(): TLCacheService = TLRedisCache()

@Single
@Named("tlHttp")
fun provideTLHttpClient(): TLNetworkClient = TLOkHttpClient()

@Factory
fun provideTLServiceFacade(db: TLDatabaseService, cache: TLCacheService): TLServiceFacade =
    TLServiceFacade(db, cache)

// ============================================================================
// Module and App for testing
// ============================================================================

@Module
@ComponentScan("examples.toplevel")
class TopLevelFunctionsModule

@KoinApplication(modules = [TopLevelFunctionsModule::class])
interface TopLevelFunctionsApp
