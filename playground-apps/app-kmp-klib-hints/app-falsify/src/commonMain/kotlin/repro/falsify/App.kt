package repro.falsify

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin
import repro.config.ConfigModule

// No lookups: the only error source is a definition inside lib-config's scanned graph.
// With -Pfalsify=true, every target MUST fail with KOIN-D001 on NeedsNeverProvided.
@KoinApplication(modules = [ConfigModule::class])
class FalsifyApp

fun initKoin() {
    startKoin<FalsifyApp>()
}
