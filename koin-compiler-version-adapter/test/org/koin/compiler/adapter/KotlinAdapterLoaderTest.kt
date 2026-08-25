package org.koin.compiler.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinAdapterLoaderTest {

    private fun v(s: String) = KotlinReleaseVersion.parseOrNull(s) ?: error("unparseable: $s")

    private val registry = listOf(
        v("2.3.20") to "adapter.k2320",
        v("2.4.0") to "adapter.k240",
    )

    @Test
    fun patchBumpWithinRegisteredLineIsSilent() {
        assertEquals(emptyList(), KotlinAdapterLoader.decide(registry, "2.4.10").warnings)
        assertEquals(emptyList(), KotlinAdapterLoader.decide(registry, "2.4.99").warnings)
    }

    @Test
    fun patchBumpWithinRegisteredLineSelectsThatLinesAdapter() {
        assertEquals("adapter.k240", KotlinAdapterLoader.decide(registry, "2.4.10").entry?.second)
    }

    @Test
    fun newMinorLineWarns() {
        val warnings = KotlinAdapterLoader.decide(registry, "2.5.0").warnings
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("newer than the newest tested line"))
    }

    @Test
    fun newMinorLineStillFallsBackToNewestAdapter() {
        assertEquals("adapter.k240", KotlinAdapterLoader.decide(registry, "2.5.0").entry?.second)
    }

    @Test
    fun olderThanFloorIsUnsupported() {
        val decision = KotlinAdapterLoader.decide(registry, "2.3.10")
        assertEquals(null, decision.entry)
        assertTrue(decision.error!!.contains("older than the oldest supported version"))
    }

    @Test
    fun unrecognizedVersionFallsBackToNewestWithWarning() {
        val decision = KotlinAdapterLoader.decide(registry, "not-a-version")
        assertEquals("adapter.k240", decision.entry?.second)
        assertTrue(decision.warnings.single().contains("unrecognized Kotlin version"))
    }

    @Test
    fun crossMajorBumpWarns() {
        val warnings = KotlinAdapterLoader.decide(registry, "3.0.0").warnings
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("newer than the newest tested line"))
    }

    @Test
    fun twoAdaptersInSameMinorLineSelectsTheHigherPatch() {
        val registryWithTwoInLine = listOf(
            v("2.3.20") to "adapter.k2320",
            v("2.4.0") to "adapter.k240",
            v("2.4.5") to "adapter.k245",
        )
        assertEquals("adapter.k245", KotlinAdapterLoader.decide(registryWithTwoInLine, "2.4.10").entry?.second)
        assertEquals(emptyList(), KotlinAdapterLoader.decide(registryWithTwoInLine, "2.4.10").warnings)
    }
}
