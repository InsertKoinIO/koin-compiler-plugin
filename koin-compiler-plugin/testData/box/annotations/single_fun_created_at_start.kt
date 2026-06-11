// FILE: test.kt
// Regression test for KTZ-4152 / GH koin#2425 (and guards the sibling paths):
// per-definition `createdAtStart = true` must be honored independently of the
// module-level flag.
//
// `@Module(createdAtStart)` and `@Singleton class` were addressed by KTZ-4048, but
// `buildFunctionDefinitionCall` — `@Single`/`@Singleton` definition FUNCTIONS inside a
// @Module — never propagated the per-definition flag, so the generated `buildSingle(...)`
// fell back to the default (false) and the eager side effect was silently lost.
//
// The module here deliberately has NO `@Module(createdAtStart = true)` — otherwise the
// module-level flag would eagerly init everything and mask any per-definition defect.
// It exercises BOTH definition shapes so the class path and the function path are each
// verified independently:
//   - eager CLASS   (@Singleton(createdAtStart = true) class)  -> buildClassDefinitionCall
//   - eager FUNCTION (@Single(createdAtStart = true) fun)       -> buildFunctionDefinitionCall
// with lazy counterparts of each.
import org.koin.dsl.koinApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.annotation.Singleton

class EagerFromFun
class LazyFromFun

object Counter {
    var eagerClassCtor = 0
    var lazyClassCtor = 0
    var eagerFunCtor = 0
    var lazyFunCtor = 0
    fun reset() { eagerClassCtor = 0; lazyClassCtor = 0; eagerFunCtor = 0; lazyFunCtor = 0 }
}

@Singleton(createdAtStart = true)
class EagerClass { init { Counter.eagerClassCtor++ } }

@Singleton
class LazyClass { init { Counter.lazyClassCtor++ } }

// @ComponentScan picks up the top-level @Singleton classes; the member @Single
// functions are this module's own definitions. NOTE: no @Module(createdAtStart) —
// each definition's own flag must stand on its own.
@Module
@ComponentScan
class TestModule {
    @Single(createdAtStart = true)
    fun eagerFun(): EagerFromFun { Counter.eagerFunCtor++; return EagerFromFun() }

    @Single
    fun lazyFun(): LazyFromFun { Counter.lazyFunCtor++; return LazyFromFun() }
}

fun box(): String {
    Counter.reset()

    val app = koinApplication { modules(TestModule().module()) }
    app.createEagerInstances()

    // Eager class + eager fun must be constructed at createEagerInstances(); lazies must not.
    if (Counter.eagerClassCtor != 1) return "FAIL: @Singleton(createdAtStart=true) class not eager (eagerClassCtor=${Counter.eagerClassCtor})"
    if (Counter.eagerFunCtor != 1) return "FAIL: @Single(createdAtStart=true) fun not eager (eagerFunCtor=${Counter.eagerFunCtor})"
    if (Counter.lazyClassCtor != 0) return "FAIL: lazy class eagerly initialized (lazyClassCtor=${Counter.lazyClassCtor})"
    if (Counter.lazyFunCtor != 0) return "FAIL: lazy fun eagerly initialized (lazyFunCtor=${Counter.lazyFunCtor})"

    val koin = app.koin
    koin.get<LazyClass>(); koin.get<LazyFromFun>()
    if (Counter.lazyClassCtor != 1 || Counter.lazyFunCtor != 1) return "FAIL: lazy defs missing after get<>()"

    // Eager defs are singletons — not re-created on get<>().
    koin.get<EagerClass>(); koin.get<EagerFromFun>()
    if (Counter.eagerClassCtor != 1 || Counter.eagerFunCtor != 1) return "FAIL: eager def re-instantiated on get<>()"

    return "OK"
}
