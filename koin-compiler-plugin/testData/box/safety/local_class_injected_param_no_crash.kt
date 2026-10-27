// FILE: test.kt
// #101 follow-up: @InjectedParam on a local class (and of a local type) must not crash the
// InjectedParam hint, which writes slot types into a cross-module hint.
import org.koin.core.annotation.InjectedParam
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory

fun box(): String {
    class LocalArg
    class LocalWithLocalArg(@InjectedParam val arg: LocalArg)
    class LocalWithNamedArg(@InjectedParam val id: String)

    val m = module {
        factory<LocalWithLocalArg>()
        factory<LocalWithNamedArg>()
    }
    val koin = koinApplication { modules(m) }.koin
    val arg = LocalArg()
    val ok = koin.get<LocalWithLocalArg> { parametersOf(arg) }.arg === arg &&
        koin.get<LocalWithNamedArg> { parametersOf("x") }.id == "x"
    return if (ok) "OK" else "FAIL"
}
