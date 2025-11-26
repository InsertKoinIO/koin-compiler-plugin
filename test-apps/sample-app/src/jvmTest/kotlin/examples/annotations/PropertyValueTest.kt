package examples.annotations

import org.junit.After
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.plugin.module.dsl.startKoin

/**
 * Test for @PropertyValue annotation.
 *
 * @PropertyValue provides default values for @Property parameters when
 * the property is not set in Koin's property store.
 */
class PropertyValueTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `property with PropertyValue default uses default when not set`() {
        println("=== Testing @PropertyValue default values ===\n")

        // Start Koin WITHOUT setting properties
        // The @PropertyValue defaults should be used
        val koin = startKoin<PropertyValueApp> {
            printLogger(Level.DEBUG)
        }.koin

        val appConfig = koin.getOrNull<AppConfig>()
        println("AppConfig: $appConfig")
        assert(appConfig != null) { "AppConfig should be resolved" }

        // Check that default values from @PropertyValue are used
        println("appName: ${appConfig?.appName}")
        println("version: ${appConfig?.version}")
        assert(appConfig?.appName == "MyDefaultApp") { "appName should use @PropertyValue default" }
        assert(appConfig?.version == "1.0.0") { "version should use @PropertyValue default" }

        println("\n=== Test Passed ===")
    }

    @Test
    fun `property with PropertyValue can be overridden`() {
        println("=== Testing @PropertyValue override ===\n")

        // Start Koin WITH properties set - should override defaults
        val koin = startKoin<PropertyValueApp> {
            printLogger(Level.DEBUG)
            properties(mapOf(
                "app.name" to "OverriddenApp",
                "app.version" to "2.0.0"
            ))
        }.koin

        val appConfig = koin.getOrNull<AppConfig>()
        println("AppConfig: $appConfig")
        assert(appConfig != null) { "AppConfig should be resolved" }

        // Check that provided values override defaults
        println("appName: ${appConfig?.appName}")
        println("version: ${appConfig?.version}")
        assert(appConfig?.appName == "OverriddenApp") { "appName should use provided value" }
        assert(appConfig?.version == "2.0.0") { "version should use provided value" }

        println("\n=== Test Passed ===")
    }
}
