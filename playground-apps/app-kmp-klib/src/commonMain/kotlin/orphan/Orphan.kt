// A3 Gate-3 (1b) native/wasm survival test for ORPHAN-path funcreqs.
//
// Package `orphan` is NOT under `playground`, so neither FirstModule nor SecondModule's default
// @ComponentScan covers it — provideOrphan is a genuine ORPHAN top-level @Single function. With a
// must-validate dependency (OrphanDep) it emits an orphan `funcreqs_orphan_OrphanThing` carrier hint
// via emitOrphanFuncReqsHints, in its own synthetic hint file. Compiling this to wasmJs/iosArm64
// proves that per-function orphan funcreqs hint file serializes to KLIB (no clash, no source-null).
// Provider-only here (nothing loads it), so it raises no compile-safety diagnostic.
package orphan

import org.koin.core.annotation.Single

class OrphanDep
class OrphanThing(val dep: OrphanDep)

@Single
fun provideOrphanDep(): OrphanDep = OrphanDep()

@Single
fun provideOrphan(dep: OrphanDep): OrphanThing = OrphanThing(dep)
