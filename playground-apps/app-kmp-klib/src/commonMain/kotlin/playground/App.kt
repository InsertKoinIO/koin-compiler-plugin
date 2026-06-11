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
