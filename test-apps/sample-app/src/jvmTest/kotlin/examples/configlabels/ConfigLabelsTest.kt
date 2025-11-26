package examples.configlabels

import examples.configlabels.defaultpkg.DefaultService
import examples.configlabels.prodpkg.ProdService
import examples.configlabels.sharedpkg.SharedService
import examples.configlabels.testpkg.TestService
import org.junit.After
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.plugin.module.dsl.startKoin

/**
 * Integration tests for @Configuration with configuration labels.
 *
 * Verifies that:
 * 1. @Configuration modules without explicit labels are discovered with "default" label
 * 2. @Configuration("test") modules are discovered when configurations = ["test"]
 * 3. @Configuration("test", "prod") modules are discovered for both test and prod
 * 4. @KoinApplication with configurations filters modules correctly
 */
class ConfigLabelsTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `default configuration discovers default modules`() {
        println("=== Testing Default Configuration ===\n")

        // DefaultApp has @KoinApplication (no configurations) = uses "default" label
        val koin = startKoin<DefaultApp> {
            printLogger(Level.DEBUG)
        }.koin

        // DefaultService should be resolved (from DefaultConfigModule with @Configuration)
        val defaultService = koin.getOrNull<DefaultService>()
        println("DefaultService: $defaultService")
        assert(defaultService != null) { "DefaultService should be resolved in default configuration" }

        // TestService should NOT be resolved (TestConfigModule has @Configuration("test"))
        val testService = koin.getOrNull<TestService>()
        println("TestService: $testService")
        assert(testService == null) { "TestService should NOT be resolved in default configuration" }

        // ProdService should NOT be resolved (ProdConfigModule has @Configuration("prod"))
        val prodService = koin.getOrNull<ProdService>()
        println("ProdService: $prodService")
        assert(prodService == null) { "ProdService should NOT be resolved in default configuration" }

        // SharedService should NOT be resolved (SharedConfigModule has @Configuration("test", "prod"))
        val sharedService = koin.getOrNull<SharedService>()
        println("SharedService: $sharedService")
        assert(sharedService == null) { "SharedService should NOT be resolved in default configuration" }

        println("\n=== Test Passed: Default Configuration ===")
    }

    @Test
    fun `test configuration discovers test modules`() {
        println("=== Testing Test Configuration ===\n")

        // TestApp has @KoinApplication(configurations = ["test"])
        val koin = startKoin<TestApp> {
            printLogger(Level.DEBUG)
        }.koin

        // DefaultService should NOT be resolved (DefaultConfigModule has @Configuration = default)
        val defaultService = koin.getOrNull<DefaultService>()
        println("DefaultService: $defaultService")
        assert(defaultService == null) { "DefaultService should NOT be resolved in test configuration" }

        // TestService should be resolved (TestConfigModule has @Configuration("test"))
        val testService = koin.getOrNull<TestService>()
        println("TestService: $testService")
        assert(testService != null) { "TestService should be resolved in test configuration" }

        // ProdService should NOT be resolved (ProdConfigModule has @Configuration("prod"))
        val prodService = koin.getOrNull<ProdService>()
        println("ProdService: $prodService")
        assert(prodService == null) { "ProdService should NOT be resolved in test configuration" }

        // SharedService SHOULD be resolved (SharedConfigModule has @Configuration("test", "prod"))
        val sharedService = koin.getOrNull<SharedService>()
        println("SharedService: $sharedService")
        assert(sharedService != null) { "SharedService should be resolved in test configuration" }

        println("\n=== Test Passed: Test Configuration ===")
    }

    @Test
    fun `prod configuration discovers prod modules`() {
        println("=== Testing Prod Configuration ===\n")

        // ProdApp has @KoinApplication(configurations = ["prod"])
        val koin = startKoin<ProdApp> {
            printLogger(Level.DEBUG)
        }.koin

        // DefaultService should NOT be resolved
        val defaultService = koin.getOrNull<DefaultService>()
        println("DefaultService: $defaultService")
        assert(defaultService == null) { "DefaultService should NOT be resolved in prod configuration" }

        // TestService should NOT be resolved
        val testService = koin.getOrNull<TestService>()
        println("TestService: $testService")
        assert(testService == null) { "TestService should NOT be resolved in prod configuration" }

        // ProdService SHOULD be resolved
        val prodService = koin.getOrNull<ProdService>()
        println("ProdService: $prodService")
        assert(prodService != null) { "ProdService should be resolved in prod configuration" }

        // SharedService SHOULD be resolved (has both "test" and "prod" labels)
        val sharedService = koin.getOrNull<SharedService>()
        println("SharedService: $sharedService")
        assert(sharedService != null) { "SharedService should be resolved in prod configuration" }

        println("\n=== Test Passed: Prod Configuration ===")
    }

    @Test
    fun `combined test and prod configuration discovers all matching modules`() {
        println("=== Testing Test + Prod Configuration ===\n")

        // TestProdApp has @KoinApplication(configurations = ["test", "prod"])
        val koin = startKoin<TestProdApp> {
            printLogger(Level.DEBUG)
        }.koin

        // DefaultService should NOT be resolved
        val defaultService = koin.getOrNull<DefaultService>()
        println("DefaultService: $defaultService")
        assert(defaultService == null) { "DefaultService should NOT be resolved in test+prod configuration" }

        // TestService SHOULD be resolved
        val testService = koin.getOrNull<TestService>()
        println("TestService: $testService")
        assert(testService != null) { "TestService should be resolved in test+prod configuration" }

        // ProdService SHOULD be resolved
        val prodService = koin.getOrNull<ProdService>()
        println("ProdService: $prodService")
        assert(prodService != null) { "ProdService should be resolved in test+prod configuration" }

        // SharedService SHOULD be resolved
        val sharedService = koin.getOrNull<SharedService>()
        println("SharedService: $sharedService")
        assert(sharedService != null) { "SharedService should be resolved in test+prod configuration" }

        println("\n=== Test Passed: Test + Prod Configuration ===")
    }
}
