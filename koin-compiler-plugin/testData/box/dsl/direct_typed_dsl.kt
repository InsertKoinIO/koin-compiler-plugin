// FILE: test.kt
// KTZ-4829: the typed safe DSL — single<T>() / factory<T>() / scoped<T>() — is registered directly
// (BeanDefinition + factory + saveMapping with a compile-time key) instead of through buildSingle.
// The block still yields a KoinDefinition, so a chained `bind` keeps working. Box runner verifies
// every key against indexKey().
package sample.dsl

import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.scoped
import org.koin.core.qualifier.named
import org.koin.core.qualifier.qualifier
import org.koin.core.annotation.Named

interface Api
interface Marker
interface Other
class Repository
class Service(val repository: Repository) : Api
class Multi : Api, Marker, Other
class Plain : Api
class Transient(val repository: Repository)
@Named("tagged")
class Tagged
class SessionScope
class InSession(val repository: Repository)
class InNamed(val repository: Repository)

fun box(): String {
    val m = module {
        single<Repository>()
        single<Service>() bind Api::class
        single<Multi>() binds arrayOf(Marker::class, Other::class)
        single<Plain>() bind Any::class          // kotlin.* bound type: the runtime bind chain is kept
        factory<Transient>()
        single<Tagged>()
        scope<SessionScope> {
            scoped<InSession>()
        }
        scope(named("session")) {
            scoped<InNamed>()
        }
    }
    val koin = koinApplication { modules(m) }.koin

    val repo = koin.get<Repository>()
    if (koin.get<Repository>() !== repo) return "FAIL: single<T>() not a singleton"
    val service = koin.get<Service>()
    if (service.repository !== repo) return "FAIL: dependency not injected"
    if (koin.getOrNull<Api>(named("dummy-never")) != null) return "FAIL: a qualified lookup matched an unqualified definition"
    if (koin.getAll<Api>().none { it === service }) return "FAIL: chained bind on a direct registration lost"
    val multi = koin.get<Multi>()
    if (koin.get<Marker>() !== multi || koin.get<Other>() !== multi) return "FAIL: binds arrayOf(...) not folded/registered"
    if (koin.getAll<Any>().none { it is Plain }) return "FAIL: kotlin.Any bind chain lost"
    if (koin.get<Transient>() === koin.get<Transient>()) return "FAIL: factory<T>() returned the same instance"
    if (koin.getOrNull<Tagged>() != null) return "FAIL: @Named class resolvable without qualifier"
    if (koin.get<Tagged>(named("tagged")) == null) return "FAIL: @Named class not resolvable with qualifier"

    val typed = koin.createScope("s1", qualifier<SessionScope>())
    if (typed.get<InSession>().repository !== repo) return "FAIL: scoped<T>() in typed scope"
    if (koin.getOrNull<InSession>() != null) return "FAIL: typed scoped definition leaked to root"

    val named = koin.createScope("s2", named("session"))
    if (named.get<InNamed>().repository !== repo) return "FAIL: scoped<T>() in named scope"
    if (koin.getOrNull<InNamed>() != null) return "FAIL: named scoped definition leaked to root"

    return "OK"
}
