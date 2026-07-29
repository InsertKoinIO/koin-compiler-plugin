// DSL module-hint native/wasm survival test. Two `single<T>()` definitions in ONE `module { }` val
// now batch into a single `koin_dsl_hints_dslModule.kt` file with two same-named `dsl_single`
// overloads (distinct signatures). Compiling this to wasmJs/iosArm64 proves the batched DSL hint file
// serializes to KLIB without a duplicate-signature clash — the same shape the annotation module-scan
// hints already use. DslService depends on DslDep (provided here), so the graph is self-consistent.
package playground

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class DslDep
class DslService(val dep: DslDep)

val dslModule = module {
    single<DslDep>()
    single<DslService>()
}

// --- includes-edge hint (A3 DSL topology carrier) KLIB survival ---
// Every `module { includes(…) }` also emits a `dslincludes_<owner>(module_<included>: Unit, …)`
// hint so the edge — which otherwise lives only in a lambda body — crosses the module boundary.
// All its parameters are Unit-typed, so uniqueness rests entirely on the owner id being in the
// function NAME; two owners with the same include count would otherwise share a descriptor and
// clash on KLIB. These three vals pin that down on wasmJs/iosArm64:
//   - dslRelayModule    : includes only, NO definitions of its own → gets a hint file containing
//                         nothing but the includes edge (the relay path).
//   - dslIncludingModule: same include count (1) as dslRelayModule → same shape, different name.
// If the naming ever regresses to a shared signature, SignatureClashDetector fails these targets.
val dslRelayModule = module {
    includes(dslModule)
}

val dslIncludingModule = module {
    includes(dslRelayModule)
}
