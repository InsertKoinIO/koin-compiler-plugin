package repro.plain

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

// #106: @ComponentScan module WITHOUT @Configuration (already broken on 2.4.10).
@Single
class Clock

@Module
@ComponentScan("repro.plain")
class PlainModule
