// FILE: test.kt
// Regression test for #83: providerOnly DSL definitions (lambda-body singles and create(::fn)
// calls) must not trigger KOIN-D001 for their bound type's constructor parameters. The plugin
// never invokes that constructor — the lambda or factory function builds the instance — so those
// parameters are not requirements.
import org.koin.dsl.module
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

// Constructor parameters (String, Int) are NOT in the DI graph — intentional.
// Before the fix, full-graph validation reported:
//   KOIN-D001: Missing dependency: kotlin.String required by dsl:Probe (parameter 'a')
//   KOIN-D001: Missing dependency: kotlin.Int   required by dsl:Probe (parameter 'b')
class Probe(val a: String, val b: Int)

private fun makeProbe(): Probe = Probe("x", 1)

fun box(): String {
    val koin = startKoin {
        modules(
            module {
                single { Probe("x", 1) }         // lambda builds it — providerOnly
            },
            module {
                single { makeProbe() }            // factory function — providerOnly
            }
        )
    }.koin

    val p1 = koin.get<Probe>()
    stopKoin()

    return if (p1.a == "x" && p1.b == 1) "OK" else "FAIL"
}
