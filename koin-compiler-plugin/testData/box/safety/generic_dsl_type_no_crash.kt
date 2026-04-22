// FILE: test.kt
// Regression for #18: a DSL definition on a generic class — `single<Navigator<Key>>()` —
// must not crash the Kotlin/Native klib signature mangler. Pre-fix, the plugin emitted a
// hint whose parameter type carried the class's free type parameter `T`; Konan serialized
// that and threw "No container found for type parameter 'T'". JVM was lenient, iOS/Native
// was not.
//
// Post-fix, `hintParameterType` erases type args to `Any?` (raw class form), so the hint
// has no free type variables. The golden .fir.ir.txt must show the hint parameter typed
// as `Navigator` (not `Navigator<T>`) — validates the erasure path.
//
// Runtime Koin doesn't discriminate Navigator<A> from Navigator<B> anyway (type erasure),
// so compile-safety loses no fidelity compared to runtime behaviour.
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

interface Key
class AppKey : Key

class Navigator<T : Key>(val initial: T) {
    fun describe(): String = "nav=${initial::class.simpleName}"
}

fun box(): String {
    val koin = koinApplication {
        modules(module {
            single { Navigator(AppKey()) }
        })
    }.koin

    val nav = koin.get<Navigator<AppKey>>()
    return if (nav.describe() == "nav=AppKey") "OK" else "FAIL: generic DSL type broke"
}
