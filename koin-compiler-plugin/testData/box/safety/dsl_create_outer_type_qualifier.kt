// FILE: test.kt
import org.koin.core.annotation.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

// Issue #41 / PR #48 regression guard: a TYPE qualifier on the outer DSL call —
// `single(qualifier = named<DbQualifier>()) { create(::...) }` — must be propagated
// into the safety hint. The `named<T>()` overload has ZERO value parameters, so the
// extractor must NOT index value-argument slot 0 (that crashed the compiler with
// "No such value argument slot: 0 (total=0)" before the compat-shim fix).
class DbQualifier
class Database
fun createDatabase(): Database = Database()

class Repo(@Qualifier(DbQualifier::class) val db: Database)

val appModule = module {
    single(qualifier = named<DbQualifier>()) { create(::createDatabase) }
    single<Repo>()
}

fun box(): String {
    val koin = koinApplication {
        modules(appModule)
    }.koin

    return if (koin.get<Repo>().db is Database) "OK" else "FAIL"
}
