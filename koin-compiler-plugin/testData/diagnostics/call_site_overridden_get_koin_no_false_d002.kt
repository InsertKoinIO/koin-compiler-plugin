// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58: a component that overrides `getKoin()` returns its own Koin, not the one `startKoin`
// builds here.
//
// EXPECTED: no KOIN-D002 for `get<Missing>()`, one KOIN-W009 instead.
package testpkg

import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class LocalThing
class Missing

fun main() {
    startKoin { modules(module { single<LocalThing>() }) }
}

object IsolatedContext {
    val koin: Koin = koinApplication { }.koin
}

class IsolatedScreen : KoinComponent {
    override fun getKoin(): Koin = IsolatedContext.koin
    fun load(): Missing = getKoin().get<Missing>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, objectDeclaration, override,
propertyDeclaration */
