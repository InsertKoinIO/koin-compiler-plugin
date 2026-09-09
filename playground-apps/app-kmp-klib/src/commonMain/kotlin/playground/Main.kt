package playground

import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication

// KTZ-4829 off-JVM proof: starts Koin with the plugin-generated modules and resolves every
// definition by primary type, qualifier and injected parameter. Compiled with
// verifyGeneratedKeys = true, so the run fails at module load if any compile-time index key
// differs from what indexKey() builds on this platform.
fun main() {
    val koin = koinApplication {
        modules(FirstModule().module(), SecondModule().module())
    }.koin
    koin.createEagerInstances()

    val greeter = koin.get<Greeter> { parametersOf("wasm-or-native") }
    check(greeter.name == "wasm-or-native") { "injected param lost" }
    check(koin.get<Consumer>().dep === koin.get<Dep>()) { "Consumer/Dep not wired" }
    check(koin.get<ApiClient>(named("auth")).tag == "auth") { "named auth client" }
    check(koin.get<ApiClient>(named("plain")).tag == "plain") { "named plain client" }
    check(koin.get<EagerService>() === koin.get<EagerService>()) { "eager single not a singleton" }

    println("KOIN_DIRECT_REGISTRATION_OK")
}
