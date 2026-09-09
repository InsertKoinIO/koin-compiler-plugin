// Regression test: relaying a module's hints must EMIT both @Named providers of the same type,
// not collapse them into one.
//
// A hint's erased signature is deliberately NOT a unique identity on the annotation path: a
// StringQualifier's value is carried in a parameter NAME with type Unit
// (`qualifier_<sanitized>: Unit`), so `componentscan_<module>_single(Url, Unit)` is what BOTH
// @Named("baseUrl") and @Named("authUrl") providers of `Url` render as. Duplicate-signature
// handling keyed on (name, erased parameter types) therefore cannot tell them apart, and an
// earlier shape of that handling DROPPED the second -- throwing away a distinct provider. The
// current shape appends a `dupN` marker instead, so both stay on the wire.
//
// WHAT THIS TEST GUARDS, precisely: the golden IR dump for `:mid` must contain BOTH relayed
// `componentscan_leaf_LeafModule_single` hints, the second carrying the `dupN` marker. Reverting
// the emission to a drop removes one hint from that golden, which is the RED signal.
//
// WHAT IT DOES NOT GUARD: whether a differently-qualified pair survives being READ BACK.
// `discoverModuleScanDefinitions` dedupes decoded hints on type alone, qualifier-blind, so `:app`
// currently resolves only one of the two providers -- visible in this file's own `:app` golden.
// That is a separate pre-existing defect, unaffected by the emission strategy (it reproduces the
// same with a drop, with this disambiguation, and with no dedupe at all). Do not "fix" this test
// by adjusting the emission; the decode site is the one to change, and when it is, this golden
// will legitimately gain the second provider at `:app`.
//
// box() below asserts runtime behavior is correct either way -- real codegen resolves
// LeafModule::class directly and never reads these hints, so the loss is compile-time only.
//
// Separate `// MODULE:` units are required: in one compilation the scan finds the providers locally
// and skips the hint path entirely, so the relay never runs.

// MODULE: leaf
// FILE: leaf/Providers.kt
package leaf

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

class Url(val value: String)

@Single
@Named("baseUrl")
fun provideBaseUrl(): Url = Url("https://base.example")

@Single
@Named("authUrl")
fun provideAuthUrl(): Url = Url("https://auth.example")

@Module
@Configuration
@ComponentScan("leaf")
class LeafModule

// MODULE: mid(leaf)
// FILE: mid/MidModule.kt
package mid

import leaf.LeafModule
import leaf.Url
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

// The relay: :app depends on :mid only, so LeafModule's own hints are off :app's classpath and
// have to be re-published here for :app to see them at all.
@Module(includes = [LeafModule::class])
@Configuration
@ComponentScan("mid")
class MidModule

// The consumer lives here, not in :app -- with non-transitive `implementation` deps :app cannot
// reference the `Url` TYPE at all. Its requirements still reach :app only through the relayed
// hints, so if either provider is lost there, :app's full-graph check raises a false KOIN-D001.
@Single
class UrlPair(
    @Named("baseUrl") private val base: Url,
    @Named("authUrl") private val auth: Url,
) {
    // Returns a String so :app can assert without referencing `leaf.Url`, which is genuinely off
    // its classpath (non-transitive `implementation`) -- the same reason the relay has to exist.
    fun summary(): String = "${base.value}|${auth.value}"
}

// MODULE: app(mid)
// FILE: test.kt
import mid.UrlPair
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
class App

fun box(): String {
    val koin = startKoin<App> { }.koin

    // Resolving UrlPair forces BOTH qualified providers through the relay.
    val summary = koin.get<UrlPair>().summary()
    if (summary != "https://base.example|https://auth.example") return "FAIL: got $summary"

    return "OK"
}
