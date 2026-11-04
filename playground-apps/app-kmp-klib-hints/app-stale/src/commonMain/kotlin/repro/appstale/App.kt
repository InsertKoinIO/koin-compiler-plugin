package repro.appstale

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin
import repro.stale.StaleModule

// Expect KOIN-W008 on KLIB targets (graph unverifiable), never a silent pass.
@KoinApplication(modules = [StaleModule::class])
class StaleApp

fun initKoin() {
    startKoin<StaleApp>()
}
