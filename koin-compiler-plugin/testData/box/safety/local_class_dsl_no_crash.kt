// FILE: test.kt
// GH #89 follow-up / #101: classes with no ClassId (local class, anonymous object) declared as a
// DSL definition must not crash hint registration. Typical test-fixture shape.
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.core.qualifier.named

interface Repo
interface Clock
class Tagged

fun box(): String {
    class FakeRepo : Repo
    abstract class LocalApi
    class FakeApi : LocalApi()
    class LocalTag

    val m = module {
        single<Repo> { FakeRepo() }
        single { FakeRepo() }
        single<Clock> { object : Clock {} }
        // Inferred T is the anonymous type itself: Koin registers it under that, not under Clock.
        factory { object : Clock {} }
        single { FakeApi() } bind LocalApi::class
        // Nameable contributed type, local type qualifier: the qualifier alone must skip the hint.
        single(named<LocalTag>()) { Tagged() }
    }
    val koin = koinApplication { modules(m) }.koin
    val ok = koin.get<Repo>() is FakeRepo &&
        koin.get<FakeRepo>() is FakeRepo &&
        koin.get<LocalApi>() is FakeApi &&
        koin.getAll<Clock>().size == 1 &&
        koin.get<Tagged>(named<LocalTag>()) is Tagged
    return if (ok) "OK" else "FAIL"
}
