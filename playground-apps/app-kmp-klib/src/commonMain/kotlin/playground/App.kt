package playground

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
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

// Two QUALIFIED providers of the same return type — ordinary Koin, and the shape that forced the
// funcreqs carrier to key on (return type, qualifier) instead of return type alone. Both are also
// seen by FirstModule and SecondModule's @ComponentScan, so this simultaneously exercises the
// compilation-wide dedup. Two carriers now exist for one return type, distinguished only by the
// `__q_` suffix in the function name: if that suffix were ever dropped, the two would share a
// signature and the KLIB serializer fails the build here (JVM would silently overwrite instead).
class AuthDep
class PlainDep
class ApiClient(val tag: String)

@Single
fun provideAuthDep(): AuthDep = AuthDep()

@Single
fun providePlainDep(): PlainDep = PlainDep()

@Single
@Named("auth")
fun authApiClient(dep: AuthDep): ApiClient = ApiClient("auth")

@Single
@Named("plain")
fun plainApiClient(dep: PlainDep): ApiClient = ApiClient("plain")

// @Single on an `object` bound to an interface (compiler#77). Must reference the
// singleton INSTANCE and still emit the .bind() chain.
interface Settings {
    val label: String
}

@Single([Settings::class])
object SettingsHolder : Settings {
    override val label: String = "settings"
}

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
