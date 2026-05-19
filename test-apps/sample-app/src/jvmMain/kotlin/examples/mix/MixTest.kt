package examples.mix

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

@Module
@ComponentScan
@Configuration
@KoinApplication
class MixModuleApp

@Singleton
class MixA()

class MixB(val a: MixA)

val mixModule = module {
    single<MixB>()
}