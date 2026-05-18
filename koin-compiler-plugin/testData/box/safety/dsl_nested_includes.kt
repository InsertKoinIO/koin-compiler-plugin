// FILE: test.kt
// Regression test for KTZ-4049 / GH koin#2400.
//
// Reporter's pattern (3 levels of `module { includes(other) }` composition):
//   :app    joinedModule = module { includes(dataModule) }
//   :data   dataModule   = module { includes(dbModule); /* ... */ }
//   :db     dbModule     = module { single<Database>() }
//
// The compile-safety reachability walker must follow nested DSL `includes(...)` —
// previously misreported `dbModule` as unreachable because only top-level
// `modules(joinedModule)` was traced. Already fixed in `7c1213e` ("0.5.0 - DSL
// module tracking") via `KoinDSLTransformer._moduleIncludes` +
// `CallSiteValidator.computeReachableModules` BFS. This test pins the behavior
// so a future walker refactor can't silently regress.
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Database

val dbModule = module {
    single<Database>()
}

val dataModule = module {
    includes(dbModule)
}

val joinedModule = module {
    includes(dataModule)
}

fun box(): String {
    val koin = koinApplication { modules(joinedModule) }.koin
    val db = koin.get<Database>()
    return if (db != null) "OK" else "FAIL"
}
