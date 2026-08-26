// FILE: test.kt
// singleOf(::Ctor) { named("x") } — string qualifier from the options block. Two definitions of
// the same type, disambiguated by qualifier — proves the qualifier is actually captured and used
// for resolution, not just silently accepted.
import org.koin.core.module.dsl.named
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class Repository(val label: String)

val appModule = module {
    singleOf(::makeA) { named("a") }
    singleOf(::makeB) { named("b") }
}

fun makeA() = Repository("a")
fun makeB() = Repository("b")

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val a = koin.get<Repository>(named("a"))
    val b = koin.get<Repository>(named("b"))
    return if (a.label == "a" && b.label == "b") "OK" else "FAIL: a=${a.label}, b=${b.label}"
}
