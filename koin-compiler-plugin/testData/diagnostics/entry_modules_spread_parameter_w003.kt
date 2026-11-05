// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// KOIN-W003 names the operand compile safety cannot read, not the expression around it: here the
// spread `*module` wraps a function parameter. A parameter means the modules come from the callers,
// so the message says there is nothing to change at this site.
//
// EXPECTED: one KOIN-W003 naming parameter 'module', with "nothing to change" advice. No D001/W001.
package testpkg

import org.koin.core.context.startKoin
import org.koin.core.module.Module

fun boot(vararg module: Module) {
    startKoin {
        modules(*module)
    }
}

/* GENERATED_FIR_TAGS: functionDeclaration, lambdaLiteral, outProjection, vararg */
