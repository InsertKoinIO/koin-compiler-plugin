package mixedfeature

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

// GH #124: a scanned class needs a type that only this module's DSL provides.
// Not @Configuration, so other sample apps don't discover it without the DSL module.
@Module
@ComponentScan
class MixedFeatureModule

@Singleton
class MixedScannedService internal constructor(private val provided: MixedDslProvided) {
    fun message(): String = provided.message()
}

internal class MixedDslProvided {
    fun message(): String = "provided by DSL"
}

internal val mixedFeatureInnerModule = module {
    single<MixedDslProvided>()
}

val mixedFeatureDslModule = module {
    includes(mixedFeatureInnerModule)
}
