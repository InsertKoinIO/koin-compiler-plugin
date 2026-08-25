// FILE: test.kt
// Three levels of function-returned modules chained via includes(fnCall()) — mirrors
// dsl_nested_includes.kt (val-based) for the function-based case.
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Database

fun dbModule() = module {
    single<Database>()
}

fun dataModule() = module {
    includes(dbModule())
}

fun joinedModule() = module {
    includes(dataModule())
}

fun box(): String {
    val koin = koinApplication { modules(joinedModule()) }.koin
    val db = koin.get<Database>()
    return if (db != null) "OK" else "FAIL"
}
