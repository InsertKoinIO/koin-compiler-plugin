package examples.annotations.features.sub

import org.koin.core.annotation.Factory
import org.koin.core.annotation.Singleton

// These classes are in examples.annotations.features.sub
// They should be picked up by @ComponentScan on examples.annotations.features (recursive)

@Singleton
class SubpackageService

@Factory
class SubpackageRepository(val service: SubpackageService)
