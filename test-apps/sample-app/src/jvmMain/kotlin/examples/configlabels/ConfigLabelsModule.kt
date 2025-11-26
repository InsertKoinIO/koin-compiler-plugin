package examples.configlabels

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module

/**
 * Test for @Configuration with configuration labels.
 *
 * Tests:
 * 1. Default label behavior (no explicit labels)
 * 2. Explicit "test" label
 * 3. Multiple labels ("test", "prod")
 * 4. @KoinApplication with configurations filter
 *
 * Services are in separate packages:
 * - examples.configlabels.defaultpkg.DefaultService
 * - examples.configlabels.testpkg.TestService
 * - examples.configlabels.prodpkg.ProdService
 * - examples.configlabels.sharedpkg.SharedService
 */

// === Modules with different configuration labels ===

// Default configuration module
@Module
@ComponentScan("examples.configlabels.defaultpkg")
@Configuration  // No explicit labels = "default" label
class DefaultConfigModule

// Test configuration module
@Module
@ComponentScan("examples.configlabels.testpkg")
@Configuration("test")
class TestConfigModule

// Production configuration module
@Module
@ComponentScan("examples.configlabels.prodpkg")
@Configuration("prod")
class ProdConfigModule

// Shared module in both test and prod configurations
@Module
@ComponentScan("examples.configlabels.sharedpkg")
@Configuration("test", "prod")
class SharedConfigModule

// === Apps with different configuration filters ===

// App using default configuration - should discover DefaultConfigModule
@KoinApplication  // configurations = [] = uses "default" label
object DefaultApp

// App using test configuration - should discover TestConfigModule + SharedConfigModule
@KoinApplication(configurations = ["test"])
object TestApp

// App using prod configuration - should discover ProdConfigModule + SharedConfigModule
@KoinApplication(configurations = ["prod"])
object ProdApp

// App using both test and prod - should discover TestConfigModule + ProdConfigModule + SharedConfigModule
@KoinApplication(configurations = ["test", "prod"])
object TestProdApp
