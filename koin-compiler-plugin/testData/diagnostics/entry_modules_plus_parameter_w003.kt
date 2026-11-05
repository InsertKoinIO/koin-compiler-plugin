// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// `modules(param.asList() + module { })`: the `+` and the `.asList()` are shapes compile safety
// follows; the operand it cannot read is the parameter underneath. KOIN-W003 must name that
// parameter, not "call 'plus()'".
//
// EXPECTED: one KOIN-W003 naming parameter 'modules', with "nothing to change" advice. No D001/W001.
package testpkg

import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class Clock

fun boot(modules: Array<Module>) = koinApplication {
    modules(modules.asList() + module { single { Clock() } })
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, functionDeclaration, lambdaLiteral */
