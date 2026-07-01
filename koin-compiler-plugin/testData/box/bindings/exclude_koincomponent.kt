// FILE: test.kt
// Issue #43: a @Single class implementing KoinComponent must NOT auto-bind the KoinComponent
// marker interface (that would let get<KoinComponent>() resolve to an arbitrary component —
// silent wrong-instance resolution). Its real interface (Repository) IS bound.
package testpkg
import org.koin.core.component.KoinComponent
import org.koin.core.annotation.Single
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.dsl.koinApplication

interface Repository
@Single
class MyRepo : Repository, KoinComponent

@Module
@ComponentScan("testpkg")
class AppModule

fun box(): String {
    val koin = koinApplication { modules(AppModule().module()) }.koin
    val byRepo = koin.getOrNull<Repository>()
    val byComponent = koin.getOrNull<KoinComponent>()
    return if (byRepo is MyRepo && byComponent == null) "OK" else "FAIL: KoinComponent over-bound"
}
