// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #101: a local class gets no @InjectedParam hint (no ClassId), but it is still indexed locally,
// so a wrong parametersOf(...) in the same module must still report KOIN-D005.
package testpkg

import org.koin.core.annotation.InjectedParam
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory

fun useIt() {
    class LocalSvc(@InjectedParam val id: String)
    val koin = koinApplication { modules(module { factory<LocalSvc>() }) }.koin
    koin.get<LocalSvc> { parametersOf("a", "b") }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localClass, localProperty,
primaryConstructor, propertyDeclaration, stringLiteral */
