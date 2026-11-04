// DISABLE_COMPILE_SAFETY
// FILE: test.kt
// #101 reports the "No classId" crash with compileSafety = false too. Not reproduced: the hints
// that crash are only emitted with safety on (this passes on pre-fix code). Guards that it stays so.
import org.koin.core.annotation.InjectedParam
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory

interface Repo
interface Clock
class Tagged

fun box(): String {
    class FakeRepo : Repo
    abstract class LocalApi
    class FakeApi : LocalApi()
    class LocalTag
    class LocalWithArg(@InjectedParam val id: String)

    val m = module {
        single { FakeRepo() }
        factory { object : Clock {} }
        single { FakeApi() } bind LocalApi::class
        single(named<LocalTag>()) { Tagged() }
        factory<LocalWithArg>()
    }
    val koin = koinApplication { modules(m) }.koin
    val ok = koin.get<FakeRepo>() is FakeRepo &&
        koin.get<LocalApi>() is FakeApi &&
        koin.get<Tagged>(named<LocalTag>()) is Tagged &&
        koin.get<LocalWithArg> { parametersOf("x") }.id == "x"
    return if (ok) "OK" else "FAIL"
}
