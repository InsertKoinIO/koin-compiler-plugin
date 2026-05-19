package examples.mix

import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.plugin.module.dsl.startKoin

class MixTest {

    @Test
    fun testMix() {
        val koin = startKoin<MixModuleApp> {
            modules(mixModule)
            printLogger(level = Level.DEBUG)
        }.koin

        val b = koin.get<MixB>()
        println("Got MixB with MixA: ${b.a}")

        stopKoin()
    }

}