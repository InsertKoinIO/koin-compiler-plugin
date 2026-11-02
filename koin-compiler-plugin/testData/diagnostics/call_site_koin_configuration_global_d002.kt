// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// #58 guard (#38): a Compose app's only entry point is `koinConfiguration { }`, passed to
// `KoinApplication(configuration = …)`. It is that app's global context, so global call sites are
// still judged.
//
// EXPECTED: KOIN-D002 for `get<Missing>()`.
package testpkg

import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class LocalThing
class Missing

val appConfig = koinConfiguration { modules(module { single<LocalThing>() }) }

class Screen : KoinComponent {
    fun load(): Missing = get<Missing>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, propertyDeclaration */
