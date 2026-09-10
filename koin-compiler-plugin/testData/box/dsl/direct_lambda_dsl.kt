// FILE: test.kt
// KTZ-4829: koin-core's own inline DSL with a lambda body — single { create(::F) }, with a literal
// qualifier or createdAtStart, factory { }, scoped { } — is registered directly when every part of
// the key is literal at the call site. The inner create(::F) is rewritten first, the lambda is
// passed through unchanged.
package sample.dsl

import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.create
import org.koin.core.qualifier.named
import org.koin.core.qualifier.qualifier

interface Api
class Repository
class Service(val repository: Repository) : Api
class Other(val repository: Repository)
object Counter { var eager = 0 }
class Eager { init { Counter.eager++ } }
class SessionScope
class InSession(val repository: Repository)

fun box(): String {
    Counter.eager = 0
    val m = module {
        single { create(::Repository) }
        single<Api> { create(::Service) }
        single(named("other")) { create(::Other) }
        single(createdAtStart = true) { create(::Eager) }
        factory { Other(get()) }
        scope<SessionScope> {
            scoped { create(::InSession) }
        }
    }
    val app = koinApplication { modules(m) }
    val koin = app.koin

    val repo = koin.get<Repository>()
    if (koin.get<Repository>() !== repo) return "FAIL: single { create } not a singleton"
    val api = koin.get<Api>()
    if (api !is Service || api.repository !== repo) return "FAIL: single<Api> { create(::Service) } registered under the wrong type"
    if (koin.getOrNull<Service>() != null) return "FAIL: single<Api> { create(::Service) } must register Api, not Service"
    if (koin.get<Other>(named("other")).repository !== repo) return "FAIL: literal named() qualifier lost"
    if (koin.get<Other>() === koin.get<Other>()) return "FAIL: factory { } returned the same instance"

    app.createEagerInstances()
    if (Counter.eager != 1) return "FAIL: createdAtStart = true literal lost (eager=${Counter.eager})"

    val scope = koin.createScope("s1", qualifier<SessionScope>())
    if (scope.get<InSession>().repository !== repo) return "FAIL: scoped { create } in typed scope"
    if (koin.getOrNull<InSession>() != null) return "FAIL: scoped definition leaked to root"

    return "OK"
}
