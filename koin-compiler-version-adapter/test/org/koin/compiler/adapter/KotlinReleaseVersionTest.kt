package org.koin.compiler.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class KotlinReleaseVersionTest {

    private fun v(s: String) = KotlinReleaseVersion.parseOrNull(s) ?: error("unparseable: $s")

    @Test
    fun parsesStableVersions() {
        val version = v("2.3.20")
        assertEquals(2, version.major)
        assertEquals(3, version.minor)
        assertEquals(20, version.patch)
        assertEquals(KotlinReleaseVersion.Maturity.STABLE, version.maturity)
    }

    @Test
    fun parsesPreReleaseQualifiers() {
        assertEquals(KotlinReleaseVersion.Maturity.BETA, v("2.4.0-Beta1").maturity)
        assertEquals(KotlinReleaseVersion.Maturity.RC, v("2.4.0-RC2").maturity)
        assertEquals(KotlinReleaseVersion.Maturity.DEV, v("2.4.20-dev-835").maturity)
        assertEquals(KotlinReleaseVersion.Maturity.SNAPSHOT, v("2.4.255-SNAPSHOT").maturity)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(KotlinReleaseVersion.parseOrNull("unknown"))
        assertNull(KotlinReleaseVersion.parseOrNull(""))
        assertNull(KotlinReleaseVersion.parseOrNull("kotlin"))
    }

    @Test
    fun ordersByLineThenMaturity() {
        assertTrue(v("2.3.20") < v("2.4.0"))
        assertTrue(v("2.3.21") < v("2.4.0-Beta1"))
        assertTrue(v("2.4.0-Beta1") < v("2.4.0-RC1"))
        assertTrue(v("2.4.0-RC1") < v("2.4.0"))
        assertTrue(v("2.4.0") < v("2.4.20-dev-835"))
    }

    @Test
    fun preReleaseSelectsItsOwnLine() {
        // A 2.4.0 pre-release carries the 2.4 compiler ABI: it must select the
        // 2.4.0 adapter, not fall back to 2.3.20.
        assertTrue(v("2.4.0-Beta1").lineAtLeast(v("2.4.0")))
        assertTrue(v("2.4.0-dev-2124").lineAtLeast(v("2.4.0")))
        assertFalse(v("2.3.21").lineAtLeast(v("2.4.0")))
    }

    @Test
    fun floorBoundary() {
        assertTrue(v("2.3.20").lineAtLeast(v("2.3.20")))
        assertTrue(v("2.3.21").lineAtLeast(v("2.3.20")))
        assertFalse(v("2.3.10").lineAtLeast(v("2.3.20")))
        assertFalse(v("2.3.0").lineAtLeast(v("2.3.20")))
    }
}
