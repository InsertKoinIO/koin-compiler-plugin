package org.koin.compiler.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinAdapterLoaderTest {

    private fun v(s: String) = KotlinReleaseVersion.parseOrNull(s) ?: error("unparseable: $s")

    // Mirrors the shipped registry: verified versions, several sharing one adapter class.
    private val registry = listOf(
        v("2.3.20") to "adapter.k2320",
        v("2.4.0") to "adapter.k240",
        v("2.4.10") to "adapter.k240",
        v("2.4.20") to "adapter.k240",
    )

    @Test
    fun verifiedVersionIsSilent() {
        // Silence is earned per exact version by a green abi-check run, not inferred
        // from the line. 2.4.10 and 2.4.20 are separate registry entries.
        for (version in listOf("2.3.20", "2.4.0", "2.4.10", "2.4.20")) {
            assertEquals(emptyList(), KotlinAdapterLoader.decide(registry, version).warnings, version)
        }
    }

    @Test
    fun unverifiedPatchInsideARegisteredLineWarns() {
        // Was `patchBumpWithinRegisteredLineIsSilent`, which asserted 2.4.99 silent. That
        // premise is what let Kotlin 2.4.20 through as a raw NoSuchMethodError (GH #89, #99):
        // Kotlin ships feature releases in the .20 patch slot, so an unverified patch inside
        // a registered line is exactly as untrusted as a new line.
        // 2.4.5 sits between the verified 2.4.0 and 2.4.10 — inside a registered line,
        // below the newest entry, and not itself verified.
        val warnings = KotlinAdapterLoader.decide(registry, "2.4.5").warnings
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("not among the verified versions"), warnings.single())

        // Above every entry it is still reported, with the "newer than" wording.
        val newer = KotlinAdapterLoader.decide(registry, "2.4.99").warnings
        assertEquals(1, newer.size)
        assertTrue(newer.single().contains("newer than the newest verified version"), newer.single())
    }

    @Test
    fun verifiedVersionSelectsItsAdapter() {
        assertEquals("adapter.k240", KotlinAdapterLoader.decide(registry, "2.4.10").entry?.second)
        assertEquals("adapter.k240", KotlinAdapterLoader.decide(registry, "2.4.20").entry?.second)
    }

    @Test
    fun unverifiedPatchStillGetsTheHighestAdapterAtOrBelowIt() {
        // Warning, not a hard stop: proceed on the best adapter we have.
        assertEquals("adapter.k240", KotlinAdapterLoader.decide(registry, "2.4.99").entry?.second)
        assertEquals("adapter.k2320", KotlinAdapterLoader.decide(registry, "2.3.30").entry?.second)
    }

    @Test
    fun newMinorLineWarns() {
        val warnings = KotlinAdapterLoader.decide(registry, "2.5.0").warnings
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("newer than the newest verified version"))
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
        assertTrue(warnings.single().contains("newer than the newest verified version"))
    }

    @Test
    fun twoAdaptersInSameMinorLineSelectsTheHigherPatch() {
        val registryWithTwoInLine = listOf(
            v("2.3.20") to "adapter.k2320",
            v("2.4.0") to "adapter.k240",
            v("2.4.5") to "adapter.k245",
        )
        assertEquals("adapter.k245", KotlinAdapterLoader.decide(registryWithTwoInLine, "2.4.10").entry?.second)
        // ...and, not being a verified entry itself, still warns: selection is by line,
        // trust is by exact release.
        assertEquals(1, KotlinAdapterLoader.decide(registryWithTwoInLine, "2.4.10").warnings.size)
    }

    /**
     * REGRESSION GUARD (GH #89, #99) — an unverified version never passes silently.
     *
     * This started as a falsifier and failed RED on 1.2.0: with a `major.minor` trust unit,
     * Kotlin 2.4.20 produced neither a warning nor an error while being handed the 2.4.0
     * adapter, so users got a raw `NoSuchMethodError` from `DslHintGenerator`. Kept
     * version-agnostic on purpose — it asks "is anything unverified reported?", so it keeps
     * biting whatever the registry happens to contain.
     */
    @Test
    fun anyUnverifiedVersionIsReported() {
        for (version in listOf("2.4.99", "2.5.0", "2.3.30", "3.0.0")) {
            val decision = KotlinAdapterLoader.decide(registry, version)
            assertTrue(
                decision.warnings.isNotEmpty() || decision.error != null,
                "Kotlin $version is not a verified version but the gate reported nothing " +
                    "(selected: ${decision.entry?.second})",
            )
        }
    }
}
