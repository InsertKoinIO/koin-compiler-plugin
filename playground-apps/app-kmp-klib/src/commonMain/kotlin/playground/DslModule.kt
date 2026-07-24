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
