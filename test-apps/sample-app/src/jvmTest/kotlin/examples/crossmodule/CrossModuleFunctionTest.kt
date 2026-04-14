package examples.crossmodule

import org.junit.Test

/**
 * Tests that cross-module top-level function discovery works for compile-time safety.
 *
 * The @Singleton function provideFeatureConfig() in sample-feature-module's "featureutil" package
 * generates a function hint that makes FeatureConfig visible during compile-time validation.
 * CrossModuleFunctionModule scans "featureutil" and "examples.crossmodule", so the safety
 * check sees FeatureConfig as provided and CrossModuleConsumer's dependency is satisfied.
 *
 * This test just verifies the compilation succeeded — the compile-time safety check
 * ran during compilation and would have caused a build error if FeatureConfig was not discoverable.
 */
class CrossModuleFunctionTest {

    @Test
    fun cross_module_function_hint_validates_at_compile_time() {
        // The fact that this test compiles and runs proves that:
        // 1. FIR generated a definition_function_single hint for provideFeatureConfig()
        // 2. IR discovered the hint during CrossModuleFunctionModule's collectAllDefinitions()
        // 3. BindingRegistry validated CrossModuleConsumer's dependency on FeatureConfig as satisfied
        // 4. FeatureModule is explicitly included from sample-feature-module.
        // 5. FeatureModule's @Singleton(binds = [FeatureLogger::class]) provider function
        //    exported its binding metadata for CrossModuleBoundFunctionConsumer.
        // If any of these steps failed, compilation would have produced:
        //   "[Koin] Missing dependency: featureutil.FeatureConfig"
        // or:
        //   "[Koin] Missing dependency: feature.FeatureLogger"
        println("Cross-module function hint compile-time validation: OK")
    }
}
