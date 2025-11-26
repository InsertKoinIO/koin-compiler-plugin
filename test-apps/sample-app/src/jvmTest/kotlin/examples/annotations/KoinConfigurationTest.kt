package examples.annotations

import examples.annotations.configtest.ConfigTestRepository
import examples.annotations.configtest.ConfigTestService
import org.junit.Test
import org.koin.core.logger.Level
import org.koin.dsl.includes
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.koinConfiguration
import org.koin.plugin.module.dsl.withConfiguration

/**
 * Test for koinConfiguration<T>() DSL function.
 *
 * koinConfiguration<T>() creates a KoinConfiguration that can be included
 * in a koinApplication or startKoin using includes().
 * This is useful for modular configuration and testing.
 */
class KoinConfigurationTest {

    @Test
    fun `koinConfiguration creates configuration to be included in koinApplication`() {
        println("=== Testing koinConfiguration<T>() with includes() ===\n")

        // koinConfiguration<T>() transforms to koinConfigurationWith(modules)
        // Use includes() to add the configuration to koinApplication
        val koin = koinApplication {
            printLogger(Level.DEBUG)
            includes(koinConfiguration<KoinConfigurationApp>())
        }.koin

        println("\n--- Testing Module Discovery ---")

        val service = koin.getOrNull<ConfigTestService>()
        println("ConfigTestService: $service")
        assert(service != null) { "ConfigTestService should be resolved from KoinConfigurationModule" }

        val repository = koin.getOrNull<ConfigTestRepository>()
        println("ConfigTestRepository: $repository")
        assert(repository != null) { "ConfigTestRepository should be resolved from KoinConfigurationModule" }

        // Close the isolated Koin instance
        koin.close()

        println("\n=== Test Passed ===")
    }

    @Test
    fun `koinConfiguration with appDeclaration lambda`() {
        println("=== Testing koinConfiguration<T>() with lambda ===\n")

        // Test with appDeclaration lambda in koinConfiguration
        val koin = koinApplication {
            includes(koinConfiguration<KoinConfigurationApp> {
                printLogger(Level.DEBUG)
            })
        }.koin

        val service = koin.getOrNull<ConfigTestService>()
        assert(service != null) { "ConfigTestService should be resolved" }

        koin.close()

        println("=== Test Passed ===")
    }

    @Test
    fun `KoinApplication withConfiguration adds modules to application`() {
        println("=== Testing KoinApplication.withConfiguration<T>() ===\n")

        // withConfiguration<T>() is an extension on KoinApplication
        // that transforms to withConfigurationWith(modules, lambda)
        val koin = koinApplication {
            printLogger(Level.DEBUG)
            withConfiguration<KoinConfigurationApp>()
        }.koin

        println("\n--- Testing Module Discovery ---")

        val service = koin.getOrNull<ConfigTestService>()
        println("ConfigTestService: $service")
        assert(service != null) { "ConfigTestService should be resolved from KoinConfigurationModule" }

        val repository = koin.getOrNull<ConfigTestRepository>()
        println("ConfigTestRepository: $repository")
        assert(repository != null) { "ConfigTestRepository should be resolved from KoinConfigurationModule" }

        koin.close()

        println("\n=== Test Passed ===")
    }

    @Test
    fun `KoinApplication withConfiguration with lambda`() {
        println("=== Testing KoinApplication.withConfiguration<T>() with lambda ===\n")

        // withConfiguration<T>() can also take an optional appDeclaration lambda
        val koin = koinApplication {
            printLogger(Level.DEBUG)
            withConfiguration<KoinConfigurationApp> {
                // Additional configuration in the lambda
                println("Inside withConfiguration lambda")
            }
        }.koin

        val service = koin.getOrNull<ConfigTestService>()
        assert(service != null) { "ConfigTestService should be resolved" }

        koin.close()

        println("=== Test Passed ===")
    }
}