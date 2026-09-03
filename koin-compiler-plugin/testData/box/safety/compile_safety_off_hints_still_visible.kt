// DISABLE_COMPILE_SAFETY
// Regression test: funcreqs_*/annotationincludes_* hints must be emitted UNCONDITIONALLY,
// regardless of this module's own koinCompiler { compileSafety } setting. A short-lived earlier
// version of this branch gated them on the LOCAL compileSafetyEnabled flag, which was wrong:
// whether these hints are needed depends on whether a DOWNSTREAM consumer module validates
// through this one, not on whether THIS module does — a producer can't know that. See
// testData/diagnostics/cross_module_producer_safety_off_missing_dep_d001.kt for the actual
// cross-module failure that local gating caused (a downstream consumer's build going silently
// green on a genuinely missing dependency). This test just asserts the 3 emission sites stay
// unconditional even when compile safety is off on the emitting module itself:
//   - orphan-path funcreqs_       (KoinAnnotationProcessor.emitOrphanFuncReqsHints)
//   - scan-path funcreqs_         (KoinAnnotationProcessor.collectTopLevelFunctionHint)
//   - annotationincludes_ carrier (KoinAnnotationProcessor.generateModuleScanHints)
// The .fir.ir.txt golden dump is the assertion surface: all three hint functions must still
// appear in it. box() proves ordinary resolution still works with safety off.

// FILE: scanned/Scanned.kt
package scanned

import org.koin.core.annotation.Single

@Single
class Dep

class ScannedThing(val dep: Dep)

@Single
fun provideScannedThing(dep: Dep): ScannedThing = ScannedThing(dep)

// FILE: orphan/Orphan.kt
package orphan

import org.koin.core.annotation.Single
import scanned.Dep

// Never scanned by any local @Module — a true orphan top-level provider.
class OrphanThing(val dep: Dep)

@Single
fun provideOrphanThing(dep: Dep): OrphanThing = OrphanThing(dep)

// FILE: includes/IncludesChain.kt
package includes

import org.koin.core.annotation.Module

@Module
class LeafModule

@Module(includes = [LeafModule::class])
class MidModule

// FILE: app/App.kt
package app

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.dsl.koinApplication
import scanned.ScannedThing
import includes.MidModule

@Module(includes = [MidModule::class])
@ComponentScan("scanned")
class AppModule

fun box(): String {
    val koin = koinApplication {
        modules(AppModule().module())
    }.koin
    val scanned = koin.get<ScannedThing>()
    return "OK"
}
