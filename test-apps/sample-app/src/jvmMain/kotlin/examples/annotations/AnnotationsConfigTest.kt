package examples.annotations

import feature.FeatureService
import feature.PremiumConsumer
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.plugin.module.dsl.startKoin

@Module
@ComponentScan
@Configuration // Tag this module as auto-discoverable
class MyModule2

@Singleton
class ConfigSingle

// @Configuration modules are auto-discovered - no need for explicit modules parameter!
@KoinApplication
object MyApp2

fun main() {
    val koin = startKoin<MyApp2> {
        printLogger(Level.DEBUG)
    }.koin

    try {
        check(koin.getOrNull<ConfigSingle>() != null) { "ConfigSingle should be resolved from MyModule2" }
        check(koin.getOrNull<FeatureService>() != null) { "FeatureService should be resolved from FeatureModule" }
        check(koin.getOrNull<PremiumConsumer>() != null) { "PremiumConsumer should be resolved from FeatureModule" }
        println("OK")
    } finally {
        stopKoin()
    }
}
