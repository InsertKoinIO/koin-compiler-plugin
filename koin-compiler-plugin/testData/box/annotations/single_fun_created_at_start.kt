// FILE: test.kt
// Regression test for KTZ-4152 / GH koin#2425:
// `@Single(createdAtStart = true)` on a DEFINITION FUNCTION inside a @Module was
// silently ignored. The module-level fix (KTZ-4048) covered `@Module(createdAtStart)`
// and `@Singleton class`, but `buildFunctionDefinitionCall` never propagated the
// per-definition flag, so the generated `buildSingle(...)` fell back to the default
// (false) and the eager side effect was lost.
//
// The module here deliberately has NO `@Module(createdAtStart = true)` — otherwise the
// module-level flag would eagerly init everything and mask the per-definition defect.
//
// Verification: only `eager()` is annotated `createdAtStart = true`, so after
// `createEagerInstances()` exactly the eager counter must be 1 and the lazy one 0.
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

class Eager
class Lazy

object Counter {
    var eagerCtor = 0
    var lazyCtor = 0
    fun reset() { eagerCtor = 0; lazyCtor = 0 }
}

@Module
class TestModule {
    @Single(createdAtStart = true)
    fun eager(): Eager { Counter.eagerCtor++; return Eager() }

    @Single
    fun lazy(): Lazy { Counter.lazyCtor++; return Lazy() }
}

fun box(): String {
    Counter.reset()

    val app = koinApplication { modules(TestModule().module()) }
    app.createEagerInstances()

    if (Counter.eagerCtor != 1) return "FAIL: @Single(createdAtStart=true) fun not eagerly initialized (eagerCtor=${Counter.eagerCtor})"
    if (Counter.lazyCtor != 0) return "FAIL: lazy @Single fun was eagerly initialized (lazyCtor=${Counter.lazyCtor})"

    val koin = app.koin
    koin.get<Lazy>()
    if (Counter.lazyCtor != 1) return "FAIL: lazy missing after get<>() (lazyCtor=${Counter.lazyCtor})"

    // Eager definition must still be a singleton — not re-created on get<>().
    koin.get<Eager>()
    if (Counter.eagerCtor != 1) return "FAIL: eager re-instantiated on get<>() (eagerCtor=${Counter.eagerCtor})"

    return "OK"
}
