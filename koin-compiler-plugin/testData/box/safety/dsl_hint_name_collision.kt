// FILE: alpha.kt
// Two DISTINCT module vals whose ids flatten to the SAME hint identifier.
//
// `flattenFqNameForHint` maps `.` to `_`, and the hint file name maps every non-alphanumeric
// character to `_`. Underscores in package segments and property names are legal Kotlin and common
// in Android projects, so the mapping is not injective:
//
//   package p.q_r  ->  val mod    ->  id p.q_r.mod  ->  p_q_r_mod
//   package p.q    ->  val r_mod  ->  id p.q.r_mod  ->  p_q_r_mod
//
// Both therefore want the file `koin_dsl_hints_p_q_r_mod.kt`, and any includes-edge hint they emit
// wants the function name `dslincludes_p_q_r_mod`.
//
// Two failure modes, neither of which the plugin currently guards:
//   - same compilation: two synthetic IrFiles with one name — a duplicate facade class, and on KLIB
//     a hard SignatureClashDetector error where JVM would silently overwrite;
//   - across modules: a consumer reconstructing the name for one module reads the OTHER module's
//     edges and definitions, silently attributing the wrong topology.
//
// The .fir.ir.txt golden is the evidence: count the `koin_dsl_hints_p_q_r_mod` FILE entries and
// check whether Alpha and Beta both survive into hints.
package p.q_r

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Alpha

val mod = module {
    single<Alpha>()
}

// FILE: beta.kt
package p.q

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Beta

val r_mod = module {
    single<Beta>()
}

// FILE: main.kt
import org.koin.core.context.startKoin
import p.q_r.mod
import p.q.r_mod

fun box(): String {
    startKoin {
        modules(mod, r_mod)
    }
    return "OK"
}
