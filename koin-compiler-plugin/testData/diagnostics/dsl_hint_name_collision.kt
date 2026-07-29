// RUN_PIPELINE_TILL: BACKEND
// FILE: alpha.kt
// Two DISTINCT module vals whose ids flatten to the SAME hint identifier (issue #75).
//
// `flattenFqNameForHint` maps `.` to `_`. Underscores in package segments and property names
// are legal Kotlin and common in Android projects, so the mapping is not injective:
//
//   package p.q_r  ->  val mod    ->  id p.q_r.mod  ->  p_q_r_mod
//   package p.q    ->  val r_mod  ->  id p.q.r_mod  ->  p_q_r_mod
//
// Both therefore want the includes-edge hint function `dslincludes_p_q_r_mod` — left
// undetected this is a silently-wrong hint on JVM (one facade class overwrites the other) or a
// KLIB SignatureClashDetector AssertionError with no source location (native/wasm). KOIN-D008
// catches this at same-compilation scope (Tier 1) with both raw ids named, unconditionally on
// the id collision itself — not only when a dslincludes_* function actually gets emitted this
// compile (that would miss the same two ids colliding the moment either module later gains an
// `includes()` call or a keep-alive hint). No separate native-target test is needed: this check
// runs at IR generation, before ANY backend serializes — compilation fails here on every target,
// so the KLIB crash this is modeling can no longer be reached at all, not just made rarer.
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

fun setup() {
    startKoin {
        modules(mod, r_mod)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral,
   primaryConstructor, propertyDeclaration */
