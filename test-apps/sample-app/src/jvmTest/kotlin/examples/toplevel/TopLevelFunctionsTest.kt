package examples.toplevel

import org.junit.After
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.core.qualifier.named
import org.koin.plugin.module.dsl.startKoin
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Tests for top-level function definitions with @Singleton/@Factory annotations.
 *
 * Top-level functions can be annotated with definition annotations and
 * discovered by @ComponentScan, just like annotated classes.
 */
class TopLevelFunctionsTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    /**
     * Test 1: Basic @Singleton on top-level function
     *
     * @Singleton fun provideTLDatabase(): TLDatabaseService
     * Should create a singleton instance.
     */
    @Test
    fun `singleton top-level function creates singleton instance`() {
        val koin = startKoin<TopLevelFunctionsApp> {
            printLogger(Level.DEBUG)
        }.koin

        val db1 = koin.getOrNull<TLDatabaseService>()
        assertNotNull(db1, "TLDatabaseService should be available from top-level function")
        println("TLDatabaseService: $db1")

        val db2 = koin.get<TLDatabaseService>()
        assertEquals(db1, db2, "Singleton should return same instance")

        println("\n@Singleton on top-level function: creates singleton")
    }

    /**
     * Test 2: @Factory on top-level function
     *
     * @Factory fun provideTLCache(): TLCacheService
     * Should create new instances each time.
     */
    @Test
    fun `factory top-level function creates new instances`() {
        val koin = startKoin<TopLevelFunctionsApp> {
            printLogger(Level.DEBUG)
        }.koin

        val cache1 = koin.getOrNull<TLCacheService>()
        assertNotNull(cache1, "TLCacheService should be available from top-level function")
        println("TLCacheService 1: $cache1")

        val cache2 = koin.get<TLCacheService>()
        assertNotEquals(cache1, cache2, "Factory should create new instances")
        println("TLCacheService 2: $cache2")

        println("\n@Factory on top-level function: creates new instances")
    }

    /**
     * Test 3: @Named qualifier on top-level function
     *
     * @Single @Named("tlHttp") fun provideTLHttpClient(): TLNetworkClient
     * Should be resolvable with qualifier.
     */
    @Test
    fun `named qualifier works on top-level function`() {
        val koin = startKoin<TopLevelFunctionsApp> {
            printLogger(Level.DEBUG)
        }.koin

        val client = koin.getOrNull<TLNetworkClient>(named("tlHttp"))
        assertNotNull(client, "TLNetworkClient should be available with @Named qualifier")
        println("TLNetworkClient (tlHttp): $client")

        println("\n@Named on top-level function: qualifier works")
    }

    /**
     * Test 4: Dependency injection via function parameters
     *
     * @Factory fun provideTLServiceFacade(db: TLDatabaseService, cache: TLCacheService): TLServiceFacade
     * Function parameters should be injected via get().
     */
    @Test
    fun `function parameters are injected as dependencies`() {
        val koin = startKoin<TopLevelFunctionsApp> {
            printLogger(Level.DEBUG)
        }.koin

        val db = koin.get<TLDatabaseService>()
        val facade1 = koin.getOrNull<TLServiceFacade>()
        assertNotNull(facade1, "TLServiceFacade should be available")
        println("TLServiceFacade: $facade1")

        // Check that dependencies were injected
        assertEquals(db, facade1.db, "Singleton db should be injected")
        println("  db: ${facade1.db}")
        println("  cache: ${facade1.cache}")

        // Factory creates new instances
        val facade2 = koin.get<TLServiceFacade>()
        assertNotEquals(facade1, facade2, "Factory should create new instances")
        assertEquals(db, facade2.db, "Same singleton db should be injected")
        assertNotEquals(facade1.cache, facade2.cache, "New cache instances for each facade")

        println("\nFunction parameters: dependencies injected correctly")
    }
}
