// Regression test: THREE @Named providers of one type relayed cross-module must all survive.
//
// The pair case (`cross_module_relay_named_qualifiers_both_survive`) is not enough: an earlier
// shape of duplicate-signature handling appended exactly one `Unit` marker to every occurrence
// after the first, so occurrences 2 and 3 both rendered as `(Url, Unit, Unit)` -- an identical
// erased signature again, and the JVM class-load / KLIB `SignatureClashDetector` failure this
// handling exists to prevent. Marker parameters are per-occurrence in count, not just in name:
// parameter NAMES do not participate in a JVM or KLIB signature, only types do.
//
// The `:mid` golden must show three relayed `componentscan_leaf_LeafModule_single` hints with
// 0, 1 and 2 trailing `Unit` markers respectively.

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

@Single
@Named("cdnUrl")
fun provideCdnUrl(): Url = Url("https://cdn.example")

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

@Module(includes = [LeafModule::class])
@Configuration
@ComponentScan("mid")
class MidModule

@Single
class UrlTriple(
    @Named("baseUrl") private val base: Url,
    @Named("authUrl") private val auth: Url,
    @Named("cdnUrl") private val cdn: Url,
) {
    fun summary(): String = "${base.value}|${auth.value}|${cdn.value}"
}

// MODULE: app(mid)
// FILE: test.kt
import mid.UrlTriple
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
class App

fun box(): String {
    val koin = startKoin<App> { }.koin
    val summary = koin.get<UrlTriple>().summary()
    if (summary != "https://base.example|https://auth.example|https://cdn.example") return "FAIL: got $summary"
    return "OK"
}
