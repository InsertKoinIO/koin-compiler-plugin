package examples.mixedfeature

import mixedfeature.MixedScannedService
import mixedfeature.mixedFeatureDslModule
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.plugin.module.dsl.startKoin
import kotlin.test.assertEquals

// GH #124: compiling this file used to fail with a false KOIN-D001 for MixedDslProvided.
class MixedFeatureTest {

    @Test
    fun scanned_class_resolves_dsl_provider_from_another_gradle_module() {
        val koin = startKoin<MixedFeatureApp> {
            modules(mixedFeatureDslModule)
        }.koin

        assertEquals("provided by DSL", koin.get<MixedScannedService>().message())

        stopKoin()
    }
}
