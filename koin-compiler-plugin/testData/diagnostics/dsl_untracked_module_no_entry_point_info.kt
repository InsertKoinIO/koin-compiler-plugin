// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// A `module { }` compile safety cannot identify, in a compilation that starts no Koin. The warning's
// consequence (assumed loaded by every entry point) cannot happen here, so it must not fail a build
// with allWarningsAsErrors: KOIN-W015 is still emitted, at INFO severity, with a consequence that
// names the module that will start Koin.
//
// EXPECTED: no error or warning (.errors.txt empty); one KOIN-W015 at the `module {` in lazy, visible
// in .locations.txt, which captures every typed diagnostic whatever its severity.
package testpkg

import org.koin.core.module.Module
import org.koin.dsl.module

class Repository

val lazyModule: Module by lazy {
    module {
        single { Repository() }
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, lambdaLiteral, nullableType, propertyDeclaration, propertyDelegate */
