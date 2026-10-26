package repro.config

import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

// #105: a second @Module in a platform source set made the commonMain hints disappear from the KLIB.
class Reporter

@Module
@Configuration
class PlatformModule {
    @Single
    fun reporter(): Reporter = Reporter()
}
