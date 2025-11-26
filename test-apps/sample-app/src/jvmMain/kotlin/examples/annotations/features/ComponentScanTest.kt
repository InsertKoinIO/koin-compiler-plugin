package examples.annotations.features

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

// ============================================================
// Test @ComponentScan package scanning behavior
// ============================================================

// --- Test 1: @Module without @ComponentScan ---
// Should NOT scan any packages, only uses includes and function definitions
@Module
class NoScanModule {
    @Singleton
    fun provideFunctionBasedService(): FunctionBasedService = FunctionBasedService()
}

class FunctionBasedService

// Intentionally NOT picked up by NoScanModule (no @ComponentScan)
@Singleton
class ShouldNotBeScanned

// --- Test 2: @Module @ComponentScan (no args) ---
// Should scan current package (examples.annotations.features) and all subpackages
@Module
@ComponentScan
class CurrentPackageScanModule

// This class IS in examples.annotations.features, so should be picked up
@Singleton
class InCurrentPackage

// --- Test 3: @Module @ComponentScan("specific.package") ---
// We can't easily test this without creating another package, but the mechanism is the same

// --- Test 4: Subpackage scanning ---
// Classes in examples.annotations.features.sub should be picked up by CurrentPackageScanModule

// --- Test Application (for JUnit tests via startKoin<T>) ---

// App to test MySecondModule (@ComponentScan with multiple packages)
@KoinApplication(modules = [examples.annotations.other.MySecondModule::class])
object MultiPackageScanApp
