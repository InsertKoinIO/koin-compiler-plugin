package examples.annotations

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module
@ComponentScan
@Configuration // Tag this module as auto-discoverable
class MyModule2

@Singleton
class ConfigSingle

// @Configuration modules are auto-discovered - no need for explicit modules parameter!
@KoinApplication
object MyApp2