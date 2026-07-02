package org.koin.compiler.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Guards the auto-binding exclusion policy (issues #43, #64).
 *
 * These framework/marker supertypes must never be auto-bound as a definition's exposed type —
 * binding them would let `get<KoinComponent>()` / `get<ViewModel>()` resolve to an arbitrary
 * annotated component (silent wrong-instance resolution). The set is consumed by BOTH the IR
 * detector (`detectAutoBindings`) and the FIR cross-module hint detector
 * (`KoinModuleFirGenerator.detectBindingClassIds`), so this is the single source of truth for
 * the exclusion. A plain unit test guards the exact FqName entries with no Koin classpath
 * dependency (the actual binding behavior is exercised by the `exclude_koincomponent` box test
 * and, for androidx ViewModel, the KMP playground).
 */
class AutoBindExclusionTest {

    @Test
    fun `framework and marker supertypes are excluded from auto-binding`() {
        val excluded = KoinPluginConstants.AUTO_BIND_EXCLUDED_SUPERTYPES
        assertTrue("kotlin.Any" in excluded, "kotlin.Any must never be auto-bound")
        // #43 — Koin marker interfaces
        assertTrue(
            "org.koin.core.component.KoinComponent" in excluded,
            "KoinComponent must never be auto-bound (#43)",
        )
        assertTrue(
            "org.koin.core.component.KoinScopeComponent" in excluded,
            "KoinScopeComponent must never be auto-bound (#43)",
        )
        // #64 — androidx ViewModel base classes (a @KoinViewModel binds ViewModel via
        // koin-core-viewmodel's runtime buildViewModel; the plugin must NOT add a duplicate)
        assertTrue(
            "androidx.lifecycle.ViewModel" in excluded,
            "androidx.lifecycle.ViewModel must never be plugin-auto-bound (#64)",
        )
        assertTrue(
            "androidx.lifecycle.AndroidViewModel" in excluded,
            "androidx.lifecycle.AndroidViewModel must never be plugin-auto-bound (#64)",
        )
    }
}
