// FILE: test.kt
import org.koin.core.annotation.Named
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

// Issue #41 / PR #48 behavior guard: when BOTH an outer DSL `qualifier=` and a
// function/class `@Named` are present, the EXPLICIT outer qualifier wins for the
// registered definition (matching the actual runtime registration site). Here the
// create target carries @Named("inner") but the definition is registered under
// named("outer"); the consumer needs @Named("outer"), so it resolves only if the
// outer qualifier took priority. (Before PR #48 the function @Named won → would
// register "inner" → false KOIN-D001 on the consumer.)
class Service

@Named("inner")
fun createService(): Service = Service()

class Consumer(@Named("outer") val service: Service)

val appModule = module {
    single(qualifier = named("outer")) { create(::createService) }
    single<Consumer>()
}

fun box(): String {
    val koin = koinApplication {
        modules(appModule)
    }.koin

    return if (koin.get<Consumer>().service is Service) "OK" else "FAIL"
}
