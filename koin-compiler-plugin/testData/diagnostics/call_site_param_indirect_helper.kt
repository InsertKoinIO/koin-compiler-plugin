// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Issue #61: a call site whose params lambda delegates to an INDIRECT helper that returns
// parametersOf(...) must NOT fire a false KOIN-D006. The plugin can only see *direct*
// parametersOf calls; when a params lambda is present but no direct parametersOf is visible,
// the shape is ambiguous and must be skipped — only a call site with NO params lambda at all
// genuinely "forgot" the params (that case still fires D006, see call_site_param_missing_d006).
package testpkg

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.parameter.parametersOf
import org.koin.core.parameter.ParametersHolder
import org.koin.dsl.koinApplication

@Module
@ComponentScan("testpkg")
class TestModule

@Factory
class Greeter(@InjectedParam val name: String)

// Indirect helper — returns parametersOf(...) but the plugin cannot trace into it.
fun makeParams(): ParametersHolder = parametersOf("World")

fun useIt() {
    val koin = koinApplication { modules(TestModule().module()) }.koin
    val g = koin.get<Greeter> { makeParams() }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, localProperty, primaryConstructor,
propertyDeclaration, stringLiteral */
