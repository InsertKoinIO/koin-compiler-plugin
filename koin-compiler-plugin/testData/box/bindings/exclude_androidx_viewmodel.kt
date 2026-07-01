// FILE: test.kt
// Issue #64: the compiler plugin must NOT add its own androidx.lifecycle.ViewModel auto-binding
// for a @KoinViewModel class. Previously detectAutoBindings() added ViewModel on top of the one
// koin-core-viewmodel's runtime `buildViewModel` already adds, so `binds` listed ViewModel TWICE.
// The precise guard is the .fir.ir.txt golden: MyViewModel's generated definition/hint must have
// NO ViewModel binding from the plugin (only its own type).
//
// NOTE: `get<ViewModel>()` may still resolve at runtime because koin-core-viewmodel's
// `buildViewModel` binds ViewModel itself (a Koin-runtime behavior outside the plugin's control,
// tracked separately). So this box test does not assert runtime ViewModel resolution — it just
// proves the module loads; the golden asserts the plugin-side de-duplication.
package testpkg
import androidx.lifecycle.ViewModel
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.dsl.koinApplication

@KoinViewModel
class MyViewModel : ViewModel()

@Module
@ComponentScan("testpkg")
class AppModule

fun box(): String {
    koinApplication { modules(AppModule().module()) }.koin
    return "OK"
}
