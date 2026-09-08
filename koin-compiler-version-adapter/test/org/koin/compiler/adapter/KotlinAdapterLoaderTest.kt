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

    /**
     * The shipped registry, read from the same resource the loader reads. An adapter that is
     * present but never selected reproduces the crash it was added to fix, so the mapping from
     * compiler version to adapter class is asserted rather than assumed.
     */
    private fun shippedRegistry(): List<Pair<KotlinReleaseVersion, String>> {
        val stream = javaClass.classLoader.getResourceAsStream(
            "META-INF/koin/kotlin-version-adapters.properties",
        ) ?: error("adapter registry not on the test classpath")
        val properties = java.util.Properties().apply { stream.use { load(it) } }
        return properties
            .map { (key, value) -> v(key.toString()) to value.toString() }
            .sortedBy { it.first }
    }

    @Test
    fun shippedRegistrySelectsTheExactAdapterForEachSupportedLine() {
        val registry = shippedRegistry()
        assertEquals("org.koin.compiler.adapter.k2320.Kotlin2320Adapter", KotlinAdapterLoader.decide(registry, "2.3.20").entry?.second)
        assertEquals("org.koin.compiler.adapter.k240.Kotlin240Adapter", KotlinAdapterLoader.decide(registry, "2.4.0").entry?.second)
        assertEquals("org.koin.compiler.adapter.k2420.Kotlin2420Adapter", KotlinAdapterLoader.decide(registry, "2.4.20").entry?.second)
    }

    @Test
    fun shippedRegistryKeepsPre2420PatchesOnThe240Adapter() {
        val registry = shippedRegistry()
        // 2.4.10 predates the 2.4.20 ABI break, so it must not pick up the 2.4.20 adapter.
        assertEquals("org.koin.compiler.adapter.k240.Kotlin240Adapter", KotlinAdapterLoader.decide(registry, "2.4.10").entry?.second)
        assertEquals(emptyList(), KotlinAdapterLoader.decide(registry, "2.4.10").warnings)
    }

    @Test
    fun shippedRegistryDoesNotWarnOnTheNewestSupportedVersion() {
        assertEquals(emptyList(), KotlinAdapterLoader.decide(shippedRegistry(), "2.4.20").warnings)
    }
}
