package examples.annotations

import feature.FeatureService
import feature.PremiumConsumer
import org.junit.After
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.plugin.module.dsl.startKoin

class AnnotationsConfigTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun run_annotations_config_test() {
        println("=== Testing @KoinApplication with explicit modules ===\n")

        // Test @KoinApplication with modules = [MyModule2::class]
        // MyModule2 uses @ComponentScan to discover @Singleton classes in the same package
        val koin = startKoin<MyApp2> {
            printLogger(Level.DEBUG)
        }.koin

        println("\n--- Testing Module (MyModule2) ---")
        val configSingle = koin.getOrNull<ConfigSingle>()
        println("ConfigSingle: $configSingle")
        assert(configSingle != null) { "ConfigSingle should be resolved from MyModule2" }

        val featureService = koin.getOrNull<FeatureService>()
        println("featureService: $featureService")
        assert(featureService != null) { "featureService should be resolved from FeatureModule" }

        assert(koin.getOrNull<PremiumConsumer>() != null) { "PremiumConsumer should be resolved from PremiumConsumer" }

        println("\n=== Test Passed ===")
    }
}

