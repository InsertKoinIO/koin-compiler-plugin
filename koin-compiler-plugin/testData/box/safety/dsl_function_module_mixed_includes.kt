// FILE: test.kt
// A single includes(...) call mixing a val-based module and a function-returned module — the
// real shape found in production (e.g. `includes(getUserModule, listUsersModule, otherModuleFn())`).
// resolveModuleRef's IrVararg handling must resolve each element independently.
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class Foo
class Bar
class Baz(val foo: Foo, val bar: Bar)

val fooModule = module {
    single<Foo>()
}

fun barModule() = module {
    single<Bar>()
}

val appModule = module {
    includes(fooModule, barModule())
    single<Baz>()
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val baz = koin.get<Baz>()
    return if (baz.foo != null && baz.bar != null) "OK" else "FAIL"
}
