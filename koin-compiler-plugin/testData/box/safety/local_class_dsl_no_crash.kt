// FILE: test.kt
// GH #89 follow-up / #101: classes with no ClassId (local class, anonymous object) declared as a
// DSL definition must not crash hint registration. Typical test-fixture shape.
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.dsl.koinApplication

interface Repo
interface Clock

fun box(): String {
    class FakeRepo : Repo
    abstract class LocalApi
    class FakeApi : LocalApi()

    val m = module {
        single<Repo> { FakeRepo() }
        single { FakeRepo() }
        single<Clock> { object : Clock {} }
        // Inferred T is the anonymous type itself: Koin registers it under that, not under Clock.
        factory { object : Clock {} }
        single { FakeApi() } bind LocalApi::class
    }
    val koin = koinApplication { modules(m) }.koin
    val ok = koin.get<Repo>() is FakeRepo &&
        koin.get<FakeRepo>() is FakeRepo &&
        koin.get<LocalApi>() is FakeApi &&
        koin.getAll<Clock>().size == 1
    return if (ok) "OK" else "FAIL"
}
