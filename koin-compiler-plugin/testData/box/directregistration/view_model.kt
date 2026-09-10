// WITH_VIEWMODEL_RUNTIME
// FILE: test.kt
// KTZ-4829: @KoinViewModel goes through direct registration as a Factory bound to
// androidx.lifecycle.ViewModel — the same shape koin-core-viewmodel's buildViewModel produces at
// runtime (createDefinition(Factory).bind(ViewModel::class)). Keys are self-checked against
// indexKey() by the box runner. Covers the root and the typed-scope variants.
package sample.vm

import androidx.lifecycle.ViewModel
import org.koin.dsl.koinApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Singleton
import org.koin.core.qualifier.named
import org.koin.core.qualifier.qualifier

@Module
@ComponentScan
class TestModule

@Singleton
class Repository

@KoinViewModel
class HomeViewModel(val repository: Repository) : ViewModel()

@KoinViewModel
@Named("detail")
class DetailViewModel : ViewModel()

class SessionScope

@KoinViewModel
@Scope(SessionScope::class)
class SessionViewModel(val repository: Repository) : ViewModel()

fun box(): String {
    val koin = koinApplication { modules(TestModule().module()) }.koin

    val home = koin.get<HomeViewModel>()
    if (home.repository !== koin.get<Repository>()) return "FAIL: HomeViewModel dependency not injected"
    if (koin.get<HomeViewModel>() === home) return "FAIL: view model must be a factory, got the same instance"

    if (koin.get<DetailViewModel>(named("detail")) == null) return "FAIL: named view model not resolved"
    if (koin.getOrNull<DetailViewModel>() != null) return "FAIL: named view model resolved without its qualifier"

    // the implicit ViewModel binding buildViewModel adds at runtime
    val asViewModels = koin.getAll<ViewModel>()
    if (asViewModels.none { it is HomeViewModel } || asViewModels.none { it is DetailViewModel }) return "FAIL: ViewModel binding missing on root view models"

    val scope = koin.createScope("s1", qualifier<SessionScope>())
    val session = scope.get<SessionViewModel>()
    if (session.repository !== koin.get<Repository>()) return "FAIL: scoped view model dependency not injected"
    if (koin.getOrNull<SessionViewModel>() != null) return "FAIL: scoped view model leaked to root"
    if (scope.getAll<ViewModel>().none { it is SessionViewModel }) return "FAIL: ViewModel binding missing on scoped view model"

    return "OK"
}
