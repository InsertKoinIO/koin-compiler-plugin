// FILE: test.kt
// Regression for #17: a non-Koin function named `bind` called inside a Koin DSL
// lambda must not crash the IR transformer. Before the fix, `collectBindType`
// matched any IrCall with simple name "bind" and then dereferenced arg[0],
// triggering `No such value argument slot in IrCallImpl: 2 (total=2)` on
// Arrow's `Raise.bind()` (0-arg extension on Either) and similar.
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

// Minimal Arrow-like shape: a receiver exposing a 0-arg `bind` extension.
// Arrow's real Raise has `fun <A> Either<Error, A>.bind(): A` — same shape:
// an extension function named `bind` with no value arguments.
class Boxed<out T>(val value: T)

class Raise {
    fun <T> Boxed<T>.bind(): T = this.value
}

fun <T> runRaise(block: Raise.() -> T): T = Raise().block()

class Service {
    fun compute(): Int = runRaise {
        // Inside this lambda, `bind()` resolves to Raise.bind — a function named
        // `bind` that the Koin plugin must NOT treat as its own DSL bind.
        Boxed(42).bind()
    }
}

fun box(): String {
    val koin = koinApplication {
        modules(module { single<Service>() })
    }.koin

    val svc = koin.get<Service>()
    return if (svc.compute() == 42) "OK" else "FAIL: non-Koin bind interaction broke"
}
