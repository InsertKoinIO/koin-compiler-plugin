// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Regression found via the app-dsl playground's own DispatchersModule.kt (real usage:
// @Dispatcher(NiaDispatchers.IO) fun dispatcherIO(): CoroutineDispatcher, referenced via
// singleOf(::dispatcherIO)): collectConstructorShorthandDef extracted the definition's own
// qualifier via qualifierExtractor.extractFromClass(targetClass) UNCONDITIONALLY — correct for
// a constructor reference (`@Named class Foo` + `singleOf(::Foo)`, qualifier lives on the class),
// wrong for a plain FUNCTION reference, where a qualifier annotation lives on the FUNCTION itself,
// never on its return type's class. buildDispatcher()'s return type (SomeDispatcher) is never
// annotated, so the definition silently registered UNQUALIFIED — colliding two differently-
// qualified singleOf registrations of the same type into one, or (as here) leaving Consumer's
// qualified requirement unmatched against any provider. Same bug SHAPE as 4db7c11 (a dropped
// qualifier collapsing distinct registrations), different call site. Fixed by branching the
// qualifier source the same way collectScopeNewDef already does: extractFromClass for a
// constructor reference, extractFromDeclaration(referencedFunction) for a function reference.
//
// EXPECTED: no diagnostics. Consumer's @Named("io") SomeDispatcher resolves against
// buildDispatcher's own @Named("io") registration.
package testpkg

import org.koin.core.annotation.Named
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface SomeDispatcher
class SomeDispatcherImpl : SomeDispatcher

@Named("io")
fun buildDispatcher(): SomeDispatcher = SomeDispatcherImpl()

class Consumer(@Named("io") val dispatcher: SomeDispatcher)

val appModule = module {
    singleOf(::buildDispatcher)
    single<Consumer>()
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral,
   primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
