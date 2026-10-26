package repro.config

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

// #105 / #106: @ComponentScan module WITH @Configuration, commonMain.
@Single
class Counter

@Module
@ComponentScan("repro.config")
@Configuration
class ConfigModule
