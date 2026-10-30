// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58: KOIN-W009 counts only the call sites no longer judged here. A call site that
// resolves, or that still gets KOIN-D002 (a local class nothing else can provide), is not one.
// A library with a preview `koinApplication { }` must not get a new warning for ordinary calls.
//
// EXPECTED: KOIN-D002 for `LocalOnly`, no KOIN-W009.
package testpkg

import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Present

fun preview() {
    koinApplication { modules(module { single<Present>() }) }
}

fun render(koin: Koin): Present = koin.get<Present>()

class Screen : KoinComponent {
    fun load(): Present = get<Present>()
}

fun local(koin: Koin) {
    class LocalOnly
    koin.get<LocalOnly>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localClass */
