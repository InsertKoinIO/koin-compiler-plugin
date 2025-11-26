package examples.isolated.sub

import org.koin.core.annotation.Factory
import org.koin.core.annotation.Singleton

// These classes are in examples.isolated.sub
// They should be picked up by @ComponentScan on examples.isolated (recursive)

@Singleton
class IsolatedSubpackageService

@Factory
class IsolatedSubpackageRepository(val service: IsolatedSubpackageService)
