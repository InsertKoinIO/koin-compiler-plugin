// FILE: test.kt
// Regression test for KTZ-4048 / GH koin#2415:
// `@Module(createdAtStart = true)` and `@Single(createdAtStart = true)` were both
// silently ignored — the generated `module(...)` and `buildSingle(...)` calls used
// Kotlin's `$default` trampoline with the bit set for `createdAtStart`, so the runtime
// substituted the parameter default (false) regardless of the annotation value. The
// reporter's KMP setup put these on an `actual class` body; the underlying defect
// applied to any `@Module class` though, so we exercise the same code path here
// without needing expect/actual machinery.
//
// Verification: a non-eager `@Singleton` increments its constructor counter only on
// first `get<T>()`. An eager `@Singleton(createdAtStart = true)` increments at
// `koinApplication { ... }.createEagerInstances()`. Before the fix the eager counter
// stayed at 0 — the module-level + per-definition `createdAtStart` flags were both
// lost in code generation.
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

object Counter {
    var eagerCtor = 0
    var lazyCtor = 0
    fun reset() { eagerCtor = 0; lazyCtor = 0 }
}

@Singleton(createdAtStart = true)
class EagerService {
    init { Counter.eagerCtor++ }
}

@Singleton
class LazyService {
    init { Counter.lazyCtor++ }
}

@Module(createdAtStart = true)
@ComponentScan
class TestModule

fun box(): String {
    Counter.reset()

    val app = koinApplication { modules(TestModule().module()) }
    app.createEagerInstances()

    // After createEagerInstances: EagerService must be constructed, LazyService must not.
    if (Counter.eagerCtor != 1) return "FAIL: EagerService not eagerly initialized (eagerCtor=${Counter.eagerCtor})"
    if (Counter.lazyCtor != 0) return "FAIL: LazyService was eagerly initialized (lazyCtor=${Counter.lazyCtor})"

    val koin = app.koin
    koin.get<LazyService>()
    if (Counter.lazyCtor != 1) return "FAIL: LazyService missing after get<>() (lazyCtor=${Counter.lazyCtor})"

    // Eager service should still be a singleton — same instance returned on get<>().
    val eagerFromGet = koin.get<EagerService>()
    if (Counter.eagerCtor != 1) return "FAIL: EagerService re-instantiated on get<>() (eagerCtor=${Counter.eagerCtor})"
    if (eagerFromGet === null) return "FAIL: EagerService get<>() returned null"

    return "OK"
}
