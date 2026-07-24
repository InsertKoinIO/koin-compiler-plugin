package playground

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

// Same @InjectedParam target collected by two @ComponentScan modules — the case
// that emitted the injectedparams_* hint twice and broke KLIB serialization
// (compiler#40 wasmJs, #44 iOS). Compiling this to wasmJs is the real fix proof:
// the KLIB serializer fails the build on duplicate signatures, passes on a single one.

@Factory
class Greeter(@InjectedParam val name: String)

// A3 Gate-3 funcreqs native/wasm survival test. provideConsumer is a top-level @Single function
// with a plain must-validate dependency (Dep) → it emits a `funcreqs_playground_Consumer(dep: Dep)`
// carrier hint. Package `playground` is covered by BOTH FirstModule and SecondModule's default
// @ComponentScan, so the function is discovered by two scan modules in ONE compilation — the exact
// shape that would duplicate the funcreqs hint and fail the KLIB serializer on wasmJs/iOS without
// the compilation-wide dedup. Dep/Consumer graph is self-consistent so no KOIN-D001 fires.
class Dep
class Consumer(val dep: Dep)

@Single
fun provideDep(): Dep = Dep()

@Single
fun provideConsumer(dep: Dep): Consumer = Consumer(dep)

class EagerService

@Module
@ComponentScan
class FirstModule {
    // Per-definition createdAtStart on a @Single function — eager init at startKoin.
    // Silently dropped before the fix (koin#2425); exercised here on the KLIB targets.
    @Single(createdAtStart = true)
    fun eager(): EagerService = EagerService()
}

@Module
@ComponentScan
class SecondModule
