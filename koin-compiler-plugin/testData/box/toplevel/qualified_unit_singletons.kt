// FILE: test.kt
// Reproducer for the klib signature-clash bug: two Unit-returning @Named @Singleton top-level
// functions in the same @ComponentScan module. Before the roster-hint fix, both functions produced
// hint functions with name `componentscanfunc_..._single` and signature (Unit, Unit) -> Unit —
// klib serialization for iOS/JS/Wasm targets rejected the duplicate signature.
package testpkg

import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Named
import org.koin.core.qualifier.named

@Module
@ComponentScan("testpkg")
@Configuration
class AppModule

@Named("initFlagsAndLogging")
@Singleton(createdAtStart = true)
fun initFlagsAndLogging() {
    // side-effect init, no return value
}

@Named("initNotifier")
@Singleton(createdAtStart = true)
fun initNotifier() {
    // side-effect init, no return value
}

fun box(): String {
    val koin = koinApplication {
        modules(AppModule().module())
    }.koin

    // createdAtStart = true means both should be instantiated at startKoin time.
    // Look up by their qualifiers to prove both are registered under distinct bindings.
    val a = koin.get<Unit>(named("initFlagsAndLogging"))
    val b = koin.get<Unit>(named("initNotifier"))

    // Can't assert much about Unit identity, but reaching this without NoBeanDefFoundException
    // is the proof that both qualified Unit singletons registered successfully.
    return "OK"
}
