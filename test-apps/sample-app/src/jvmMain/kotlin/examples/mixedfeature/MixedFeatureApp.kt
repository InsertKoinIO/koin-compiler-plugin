package examples.mixedfeature

import mixedfeature.MixedFeatureModule
import org.koin.core.annotation.KoinApplication

@KoinApplication(modules = [MixedFeatureModule::class])
object MixedFeatureApp
