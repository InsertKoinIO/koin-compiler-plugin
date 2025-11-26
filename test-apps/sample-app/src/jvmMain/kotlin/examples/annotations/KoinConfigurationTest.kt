package examples.annotations

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
@ComponentScan("examples.annotations.configtest")
@Configuration
class KoinConfigurationModule

@KoinApplication
object KoinConfigurationApp