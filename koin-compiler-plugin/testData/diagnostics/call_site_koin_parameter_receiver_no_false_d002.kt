// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58: a `Koin` received as a parameter has no traceable graph. The `koinApplication { }`
// elsewhere in the compilation builds a different, isolated instance, so it can't judge this call.
//
// EXPECTED: no KOIN-D002 for `get<Report>()`, one KOIN-W009 instead.
package testpkg

import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Report
class LocalThing

fun isolatedGraph() {
    koinApplication {
        modules(module { single<LocalThing>() })
    }
}

fun render(koin: Koin): Report = koin.get<Report>()

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral */
