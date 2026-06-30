// FILE: test.kt
import androidx.lifecycle.ViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

// Issue #49: in a module that mixes the new lambda-less DSL with classic DSL, the
// classic `viewModel { ... }` definition (org.koin.core.module.dsl.viewModel) must be
// recognized as a provider. Once any new-DSL call (single<T>()) activates compile-safety
// for the module, resolving the viewModel-provided type must NOT raise a false
// KOIN-D002 "Missing definition".
class CommonAnalytics
class FeatureViewModel(val analytics: CommonAnalytics) : ViewModel()

val featureModule = module {
    single<CommonAnalytics>()                          // new-DSL — activates checks for the module
    viewModel { FeatureViewModel(get<CommonAnalytics>()) } // classic viewModel — must register FeatureViewModel
}

fun box(): String {
    val koin = koinApplication {
        modules(featureModule)
    }.koin

    // Call-site resolution — false KOIN-D002 here if `viewModel { }` is not recognized.
    val vm = koin.get<FeatureViewModel>()
    return if (vm.analytics is CommonAnalytics) "OK" else "FAIL"
}
