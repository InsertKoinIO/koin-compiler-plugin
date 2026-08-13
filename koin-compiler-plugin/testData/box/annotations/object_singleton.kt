// FILE: test.kt
// Regression test for #77: @Single/@Singleton on a Kotlin object must reference INSTANCE,
// not call the synthesized constructor (which has no public access and caused NoSuchMethodError).
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.annotation.Singleton
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

interface Loader {
    val name: String
}

@Single([Loader::class])
object MyLoader : Loader {
    override val name: String = "my-loader"
}

@Singleton
object MyService

@Module
@ComponentScan
class AppModule

fun box(): String {
    val koin = startKoin {
        modules(AppModule().module())
    }.koin

    val loader = koin.get<Loader>()
    val service = koin.get<MyService>()

    // Must be the actual INSTANCE, not a new instance constructed via <init>
    if (loader !== MyLoader) return "FAIL: Loader is not MyLoader INSTANCE"
    if (service !== MyService) return "FAIL: MyService is not INSTANCE"
    if (loader.name != "my-loader") return "FAIL: wrong name ${loader.name}"

    stopKoin()
    return "OK"
}
