package examples.isolated

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Factory
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

// ============================================================
// Isolated test for @ComponentScan behavior
// This package is NOT scanned by any other module
// ============================================================

// --- @Module without @ComponentScan ---
// Should NOT scan any packages, only uses function definitions
@Module
@Configuration("isolated-no-scan")
class IsolatedNoScanModule {
    @Singleton
    fun provideIsolatedFunctionService(): IsolatedFunctionService = IsolatedFunctionService()
}

class IsolatedFunctionService

// This class is NOT picked up by IsolatedNoScanModule (no @ComponentScan)
@Singleton
class IsolatedClassNotScanned

// --- @Module @ComponentScan (scans this package) ---
@Module
@ComponentScan
@Configuration("isolated-scan")
class IsolatedScanModule

// This class IS picked up by IsolatedScanModule
@Singleton
class IsolatedClassScanned

// --- Subpackage for recursive scanning test ---
// (sub/IsolatedSubpackageClasses.kt)

// --- Test Applications ---

@KoinApplication(modules = [IsolatedNoScanModule::class])
object IsolatedNoScanApp

@KoinApplication(modules = [IsolatedScanModule::class])
object IsolatedScanApp
