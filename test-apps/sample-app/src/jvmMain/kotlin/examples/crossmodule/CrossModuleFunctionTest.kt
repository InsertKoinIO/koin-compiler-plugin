package examples.crossmodule

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

/**
 * Test cross-module discovery.
 * This module scans both "featureutil" (cross-module function hint) and "examples.crossmodule" (local),
 * and explicitly includes FeatureModule from sample-feature-module to validate cross-Gradle-module
 * provider function bindings.
 * The function hint from sample-feature-module should make FeatureConfig visible for safety checks.
 */
@Module(includes = [feature.FeatureModule::class])
@ComponentScan("featureutil", "examples.crossmodule")
class CrossModuleFunctionModule

/**
 * A service that depends on FeatureConfig from the cross-module function.
 * This validates that the function hint makes FeatureConfig visible for safety checks.
 */
@Singleton
class CrossModuleConsumer(val config: featureutil.FeatureConfig)

/**
 * A service that depends on FeatureLogger from the cross-module provider function with explicit bindings.
 * This validates that explicit bindings make FeatureLogger visible for safety checks.
 */
@Singleton
class CrossModuleBoundFunctionConsumer(val logger: feature.FeatureLogger)

@KoinApplication(modules = [CrossModuleFunctionModule::class])
interface CrossModuleFunctionApp
