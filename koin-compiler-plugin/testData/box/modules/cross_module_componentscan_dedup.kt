// Regression test for cross-module @ComponentScan emitting duplicate definitions.
//
// Repro: a dependency module (`networkapi`) declares @Single classes; a downstream module
// (`app`) has a @Module @ComponentScan covering the dependency's package. The downstream class
// is discovered ONLY via the dependency's generated hint functions (it is not local to `app`).
// Before the fix, that class was added once per discovery path, so the generated module body
// emitted the SAME `single { }` registration multiple times — invisible on JVM/DEX (D8 warns
// "multiple definitions" and drops the extras), a hard duplicate-declaration error on KLIB/native,
// and a duplicate registration at runtime.
//
// RED signal: the golden IR dump (`*.fir.ir.txt`). Before the fix it shows N `single { }`
// registrations (and N componentscan hint functions) per scanned class; after the fix, exactly one.
// The IR dump diff is the deterministic regression guard — verified to fail on the unfixed plugin.
//
// Note this is NOT observable at runtime: a module's definition map is keyed, so duplicate same-type
// `single { }` calls within one generated module silently collapse to last-wins on load (even with
// `allowOverride(false)`, which only guards cross-MODULE overrides). The box() below therefore only
// asserts behavior is preserved (both services still resolve); the codegen count is guarded by the
// golden file. On a KLIB/native target the same duplication is instead a hard compile error.
//
// Separate `// MODULE:` units are required — a single-compilation scan finds the class locally and
// skips the hint path (see the `localDefinitionFqNames` guard), so the duplication never surfaces.

// MODULE: networkapi
// FILE: network/api/services/Services.kt
package network.api.services

import org.koin.core.annotation.Single

@Single
class CharactersApiService

@Single
class EpisodesApiService

// MODULE: app(networkapi)
// FILE: networkmodule.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration

@Module
@Configuration
@ComponentScan("network")
class NetworkModule

// FILE: test.kt
import org.koin.dsl.koinApplication
import network.api.services.CharactersApiService
import network.api.services.EpisodesApiService

fun box(): String {
    val koin = koinApplication {
        // Disallow override so a duplicate single { } for the same type fails loudly instead of
        // silently overriding — this is what makes the pre-fix duplication observable on the JVM.
        allowOverride(false)
        modules(NetworkModule().module())
    }.koin

    koin.get<CharactersApiService>()
    koin.get<EpisodesApiService>()

    return "OK"
}
