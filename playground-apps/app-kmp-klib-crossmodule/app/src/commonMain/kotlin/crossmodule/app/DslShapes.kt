package crossmodule.app

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.create

// Native (KLIB) coverage for the DSL provider-only fix (#36/#49) and outer-qualifier propagation
// (#41): the generated `dsl_*` / provider-only hints must serialize cleanly on wasmJs / iosArm64.
class LocalConfig
val localConfig = LocalConfig()
fun provideLocalConfig(): LocalConfig = LocalConfig()

class LocalService
fun createLocalService(): LocalService = LocalService()

val dslShapesModule = module {
    single<LocalConfig> { localConfig }            // #36 existing-instance lambda (provider-only hint)
    single<LocalService> { create(::createLocalService) } // create(::fn) path
}
