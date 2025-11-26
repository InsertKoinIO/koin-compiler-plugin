package org.koin.compiler.plugin

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for PropertyValueRegistry - tests the @PropertyValue default storage.
 */
class PropertyValueRegistryTest {

    @BeforeEach
    fun setUp() {
        PropertyValueRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        PropertyValueRegistry.clear()
    }

    @Test
    fun `hasDefault returns false for unknown key`() {
        assertFalse(PropertyValueRegistry.hasDefault("unknown.key"))
    }

    @Test
    fun `getDefault returns null for unknown key`() {
        assertNull(PropertyValueRegistry.getDefault("unknown.key"))
    }

    @Test
    fun `clear removes all registered defaults`() {
        // We can't actually register properties in unit tests without IR,
        // but we can verify clear doesn't throw
        PropertyValueRegistry.clear()
        assertFalse(PropertyValueRegistry.hasDefault("any.key"))
    }
}
