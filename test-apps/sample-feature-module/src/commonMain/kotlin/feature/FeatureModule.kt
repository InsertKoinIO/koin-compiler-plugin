package feature

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton

/**
 * Feature module with @Configuration for cross-module discovery.
 * This module will be auto-discovered by @KoinApplication in sample-app.
 */
@Module
@ComponentScan
@Configuration
class FeatureModule

// --- Definitions in this module ---

/**
 * A singleton service from feature module
 */
@Singleton
class FeatureService {
    fun getMessage(): String = "Hello from FeatureService!"
}

/**
 * A repository that depends on FeatureService
 */
@Singleton
class FeatureRepository(val service: FeatureService) {
    fun getData(): String = "Data: ${service.getMessage()}"
}

/**
 * A factory that creates items
 */
@Factory
class FeatureItem(val repository: FeatureRepository) {
    val id: Int = counter++

    companion object {
        private var counter = 1
    }
}

/**
 * A named service for testing qualifier discovery
 */
@Singleton
@Named("premium")
class PremiumFeatureService {
    fun getPremiumMessage(): String = "Premium feature enabled!"
}

/**
 * A consumer that uses the named service
 */
@Singleton
class PremiumConsumer(@Named("premium") val premiumService: PremiumFeatureService) {
    fun consume(): String = premiumService.getPremiumMessage()
}
