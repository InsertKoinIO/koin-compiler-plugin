package org.koin.compiler.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class QualifierNameEncodingTest {

    private fun roundTrip(name: String) =
        assertEquals(name, KoinPluginConstants.unsanitizeQualifierName(KoinPluginConstants.sanitizeQualifierName(name)))

    @Test
    fun `latin punctuation round-trips`() = roundTrip("my.service-1")

    @Test
    fun `characters above U+00FF round-trip`() {
        roundTrip("a、b")
        roundTrip("api–v2 ✓")
    }

    @Test
    fun `dollar and digits after an escape round-trip`() {
        roundTrip("$1、001")
        roundTrip("a.1")
    }

    @Test
    fun `existing two-digit encoding is unchanged`() =
        assertEquals("my\$2eservice\$2d1", KoinPluginConstants.sanitizeQualifierName("my.service-1"))
}
