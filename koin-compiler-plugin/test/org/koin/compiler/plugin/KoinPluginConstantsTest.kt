package org.koin.compiler.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for KoinPluginConstants - verifies constant values are correct.
 */
class KoinPluginConstantsTest {

    @Test
    fun `option keys are correct`() {
        assertEquals("userLogs", KoinPluginConstants.OPTION_USER_LOGS)
        assertEquals("debugLogs", KoinPluginConstants.OPTION_DEBUG_LOGS)
        assertEquals("unsafeDslChecks", KoinPluginConstants.OPTION_UNSAFE_DSL_CHECKS)
        assertEquals("skipDefaultValues", KoinPluginConstants.OPTION_SKIP_DEFAULT_VALUES)
        assertEquals("compileSafety", KoinPluginConstants.OPTION_COMPILE_SAFETY)
    }

    @Test
    fun `definition types are correct`() {
        assertEquals("single", KoinPluginConstants.DEF_TYPE_SINGLE)
        assertEquals("factory", KoinPluginConstants.DEF_TYPE_FACTORY)
        assertEquals("scoped", KoinPluginConstants.DEF_TYPE_SCOPED)
        assertEquals("viewmodel", KoinPluginConstants.DEF_TYPE_VIEWMODEL)
        assertEquals("worker", KoinPluginConstants.DEF_TYPE_WORKER)
    }

    @Test
    fun `all definition types list contains all types`() {
        val types = KoinPluginConstants.ALL_DEFINITION_TYPES
        assertEquals(5, types.size)
        assertTrue(types.contains(KoinPluginConstants.DEF_TYPE_SINGLE))
        assertTrue(types.contains(KoinPluginConstants.DEF_TYPE_FACTORY))
        assertTrue(types.contains(KoinPluginConstants.DEF_TYPE_SCOPED))
        assertTrue(types.contains(KoinPluginConstants.DEF_TYPE_VIEWMODEL))
        assertTrue(types.contains(KoinPluginConstants.DEF_TYPE_WORKER))
    }

    @Test
    fun `hints package is correct`() {
        assertEquals("org.koin.plugin.hints", KoinPluginConstants.HINTS_PACKAGE)
    }

    @Test
    fun `hint function prefixes are correct`() {
        assertEquals("configuration_", KoinPluginConstants.HINT_FUNCTION_PREFIX)
        assertEquals("definition_", KoinPluginConstants.DEFINITION_HINT_PREFIX)
    }

    @Test
    fun `default label is correct`() {
        assertEquals("default", KoinPluginConstants.DEFAULT_LABEL)
    }

    @Test
    fun `module function name is correct`() {
        assertEquals("module", KoinPluginConstants.MODULE_FUNCTION_NAME)
    }
}
