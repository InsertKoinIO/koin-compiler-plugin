// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.parameter.parametersOf

// Regression for compiler#40 / #44 (KTZ-4151): when the SAME @InjectedParam target
// is collected by more than one module — here two @ComponentScan modules over the
// same package — getAllKnownDefinitions() returns it twice with identical target +
// @InjectedParam shape. The plugin must still emit the `injectedparams_*` hint
// function exactly ONCE. Emitting per-definition produced duplicate IR functions
// with identical signatures: tolerated on JVM, but a hard KLIB serialization
// failure on Native/JS/Wasm ("Different declarations with the same signatures").
//
// The .fir.ir.txt golden for this test must contain a single
// `injectedparams_<...>Greeter` FUN declaration.

@Factory
class Greeter(@InjectedParam val name: String)

@Module
@ComponentScan
class FirstModule

@Module
@ComponentScan
class SecondModule

fun box(): String {
    val koin = koinApplication {
        modules(FirstModule().module(), SecondModule().module())
    }.koin

    val greeter = koin.get<Greeter> { parametersOf("World") }
    return if (greeter.name == "World") "OK" else "FAIL: @InjectedParam not working"
}
