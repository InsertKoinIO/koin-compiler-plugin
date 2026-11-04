package repro.app

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.module.Module as KoinModule
import org.koin.dsl.module
import org.koin.mp.KoinPlatform.getKoin
import org.koin.plugin.module.dsl.startKoin
import repro.config.Counter
import repro.dsl.LoggerService
import repro.dsl.libDslModule
import repro.plain.Clock
import repro.plain.PlainModule

@Single
class Local

@Module
@ComponentScan("repro.app")
class AppModule

@KoinApplication(modules = [AppModule::class, PlainModule::class])
class App

class Client(val logger: LoggerService)

fun appDslModule(): KoinModule = module {
    single { Client(get<LoggerService>()) }
}

fun initKoin() {
    startKoin<App> {
        modules(libDslModule(), appDslModule())
    }
}

fun counter(): Counter = getKoin().get()
fun clock(): Clock = getKoin().get()
fun local(): Local = getKoin().get()
