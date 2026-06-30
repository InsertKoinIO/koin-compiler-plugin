// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

// Issue #36: `single<T> { existingInstance }` / `single<T> { provideX() }` must be recognized
// as a provider of the declared type T by compileSafety, regardless of the lambda body. The
// type argument T is what the definition provides; it does NOT require `create(::T)`.
class ServiceA
class ServiceB
val instanceA = ServiceA()
fun provideB(): ServiceB = ServiceB()

// Consumers exercise that both non-create lambda shapes register their declared type.
class NeedsA(val a: ServiceA)        // depends on the existing-instance definition
class NeedsB(val b: ServiceB)        // depends on the provider-function-call definition

val appModule = module {
    single<ServiceA> { instanceA }   // existing instance — must register ServiceA
    single<ServiceB> { provideB() }  // provider-function call — must register ServiceB
    single<NeedsA>()
    single<NeedsB>()
}

fun box(): String {
    val koin = koinApplication {
        modules(appModule)
    }.koin

    val ok = koin.get<NeedsA>().a === instanceA && koin.get<NeedsB>().b is ServiceB
    return if (ok) "OK" else "FAIL"
}
