package examples.annotations.features

import org.junit.After
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.core.qualifier.named
import org.koin.plugin.module.dsl.startKoin

/**
 * Tests for different definition types inside scope blocks.
 *
 * Verifies that:
 * - @Scoped generates buildScoped on ScopeDSL
 * - @Factory generates buildFactory on ScopeDSL
 * - @KoinViewModel generates buildViewModel on ScopeDSL
 * - @Named qualifiers work inside scopes
 */
class ScopeDefinitionsTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `test scoped definitions in UserSession scope`() {
        val koin = startKoin<ScopeDefinitionsApp> {
            printLogger(Level.DEBUG)
        }.koin

        // Create a UserSession scope
        val sessionScope = koin.createScope<UserSession>("user-session-1")

        println("--- Testing @Scoped inside scope block ---")

        // Test UserSessionData (scoped)
        val data1 = sessionScope.get<UserSessionData>()
        val data2 = sessionScope.get<UserSessionData>()
        println("data1: $data1")
        println("data2: $data2")
        assert(data1 === data2) { "Scoped should return same instance within scope" }

        // Test UserSessionCache (scoped, depends on UserSessionData)
        val cache1 = sessionScope.get<UserSessionCache>()
        val cache2 = sessionScope.get<UserSessionCache>()
        println("cache1: $cache1")
        println("cache2: $cache2")
        assert(cache1 === cache2) { "Scoped should return same instance within scope" }
        assert(cache1.data === data1) { "Cache should have same scoped data" }

        println("\n--- Testing @Factory inside scope block ---")

        // Test UserSessionPresenter (factory)
        val presenter1 = sessionScope.get<UserSessionPresenter>()
        val presenter2 = sessionScope.get<UserSessionPresenter>()
        println("presenter1: $presenter1")
        println("presenter2: $presenter2")
        assert(presenter1 !== presenter2) { "Factory should create new instances" }
        assert(presenter1.cache === cache1) { "Factory should inject scoped cache" }

        // Test DebugUserSessionPresenter with @Named qualifier
        val debugPresenter = sessionScope.get<DebugUserSessionPresenter>(named("debug"))
        println("debugPresenter: $debugPresenter")
        assert(debugPresenter.cache === cache1) { "Named factory should inject scoped cache" }

        println("\n--- Testing @KoinViewModel inside scope block ---")

        // Test UserSessionViewModel (viewModel)
        val vm1 = sessionScope.get<UserSessionViewModel>()
        val vm2 = sessionScope.get<UserSessionViewModel>()
        println("vm1: $vm1")
        println("vm2: $vm2")
        // ViewModels typically create new instances each time when accessed via get()
        assert(vm1.data === data1) { "ViewModel should inject scoped data" }

        // Test DetailedUserSessionViewModel with @Named qualifier
        val detailedVm = sessionScope.get<DetailedUserSessionViewModel>(named("detailed"))
        println("detailedVm: $detailedVm")
        assert(detailedVm.data === data1) { "Named ViewModel should inject scoped data" }
        assert(detailedVm.cache === cache1) { "Named ViewModel should inject scoped cache" }

        // Verify scoped definitions are NOT available at root scope
        assert(koin.getOrNull<UserSessionData>() == null) { "Scoped definition should not be at root" }
        assert(koin.getOrNull<UserSessionCache>() == null) { "Scoped definition should not be at root" }
        assert(koin.getOrNull<UserSessionPresenter>() == null) { "Scoped factory should not be at root" }

        sessionScope.close()
        println("\n✅ UserSession scope tests passed!")
    }

    @Test
    fun `test multiple scope types`() {
        val koin = startKoin<ScopeDefinitionsApp> {
            printLogger(Level.DEBUG)
        }.koin

        // Create AdminSession scope
        val adminScope = koin.createScope<AdminSession>("admin-session-1")

        println("--- Testing AdminSession scope ---")

        val adminData = adminScope.get<AdminData>()
        println("adminData: $adminData")

        val adminPresenter1 = adminScope.get<AdminPresenter>()
        val adminPresenter2 = adminScope.get<AdminPresenter>()
        println("adminPresenter1: $adminPresenter1")
        println("adminPresenter2: $adminPresenter2")
        assert(adminPresenter1 !== adminPresenter2) { "Factory should create new instances" }
        assert(adminPresenter1.data === adminData) { "Factory should inject scoped data" }

        val adminVm = adminScope.get<AdminViewModel>()
        println("adminVm: $adminVm")
        assert(adminVm.data === adminData) { "ViewModel should inject scoped data" }

        adminScope.close()
        println("\n✅ AdminSession scope tests passed!")
    }

    @Test
    fun `test named qualifiers inside scopes`() {
        val koin = startKoin<ScopeDefinitionsApp> {
            printLogger(Level.DEBUG)
        }.koin

        // Create FeatureScope
        val featureScope = koin.createScope<FeatureScope>("feature-scope-1")

        println("--- Testing @Named qualifiers inside scope ---")

        val primaryData = featureScope.get<PrimaryFeatureData>(named("primary"))
        val secondaryData = featureScope.get<SecondaryFeatureData>(named("secondary"))
        println("primaryData: $primaryData")
        println("secondaryData: $secondaryData")

        // Test FeatureConsumer that injects @Named dependencies
        val consumer = featureScope.get<FeatureConsumer>()
        println("consumer: $consumer")
        assert(consumer.primary === primaryData) { "Consumer should inject @Named primary data" }
        assert(consumer.secondary === secondaryData) { "Consumer should inject @Named secondary data" }

        featureScope.close()
        println("\n✅ Named qualifiers inside scope tests passed!")
    }

    @Test
    fun `test scope isolation between different scope instances`() {
        val koin = startKoin<ScopeDefinitionsApp> {
            printLogger(Level.DEBUG)
        }.koin

        // Create two UserSession scopes
        val scope1 = koin.createScope<UserSession>("session-1")
        val scope2 = koin.createScope<UserSession>("session-2")

        println("--- Testing scope isolation ---")

        val data1 = scope1.get<UserSessionData>()
        val data2 = scope2.get<UserSessionData>()
        println("data1 (scope1): $data1")
        println("data2 (scope2): $data2")
        assert(data1 !== data2) { "Different scopes should have different instances" }

        val cache1 = scope1.get<UserSessionCache>()
        val cache2 = scope2.get<UserSessionCache>()
        assert(cache1 !== cache2) { "Different scopes should have different cache instances" }
        assert(cache1.data === data1) { "Cache in scope1 should use scope1's data" }
        assert(cache2.data === data2) { "Cache in scope2 should use scope2's data" }

        scope1.close()
        scope2.close()
        println("\n✅ Scope isolation tests passed!")
    }

    @Test
    fun `test function-based scoped definitions`() {
        val koin = startKoin<ScopeDefinitionsApp> {
            printLogger(Level.DEBUG)
        }.koin

        // Create a UserSession scope
        val sessionScope = koin.createScope<UserSession>("user-session-func")

        println("--- Testing function-based @Scoped inside scope block ---")

        // Test scopedApiClient() - @Scoped function
        val apiClient1 = sessionScope.get<ScopedApiClient>()
        val apiClient2 = sessionScope.get<ScopedApiClient>()
        println("apiClient1: $apiClient1")
        println("apiClient2: $apiClient2")
        assert(apiClient1 === apiClient2) { "Scoped function should return same instance within scope" }

        println("\n--- Testing function-based @Factory inside scope block ---")

        // Test scopedLogger() - @Factory function
        val logger1 = sessionScope.get<ScopedLogger>()
        val logger2 = sessionScope.get<ScopedLogger>()
        println("logger1: $logger1")
        println("logger2: $logger2")
        assert(logger1 !== logger2) { "Factory function should create new instances" }

        println("\n--- Testing function-based @Factory with @Named inside scope ---")

        // Test scopedAnalytics() - @Factory @Named function
        val analytics1 = sessionScope.get<ScopedAnalytics>(named("analytics"))
        val analytics2 = sessionScope.get<ScopedAnalytics>(named("analytics"))
        println("analytics1: $analytics1")
        println("analytics2: $analytics2")
        assert(analytics1 !== analytics2) { "Named factory function should create new instances" }

        // Verify function-based definitions are NOT available at root scope
        assert(koin.getOrNull<ScopedApiClient>() == null) { "Scoped function definition should not be at root" }
        assert(koin.getOrNull<ScopedLogger>() == null) { "Scoped factory function should not be at root" }

        sessionScope.close()

        // Test function in different scope (AdminSession)
        val adminScope = koin.createScope<AdminSession>("admin-func")
        val adminApiClient = adminScope.get<ScopedApiClient>()
        println("adminApiClient: $adminApiClient")
        assert(adminApiClient !== apiClient1) { "Different scope should have different instance" }

        adminScope.close()
        println("\n✅ Function-based scoped definitions tests passed!")
    }
}
