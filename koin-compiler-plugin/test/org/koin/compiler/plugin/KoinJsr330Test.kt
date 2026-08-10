package org.koin.compiler.plugin

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the `jsr330` knob round-trips through [KoinPluginLogger]: default `true`
 * (preserves prior behavior — jakarta.inject/javax.inject processed), explicit `false`
 * flips [KoinPluginLogger.jsr330Enabled].
 *
 * `@Isolated` for the same reason as [KoinLogSeverityTest]: [KoinPluginLogger] is a
 * process-wide singleton.
 */
@Isolated
class KoinJsr330Test {

    @AfterEach
    fun restoreLoggerDefaults() {
        KoinPluginLogger.init(
            collector = MessageCollector.NONE,
            userLogs = false,
            debugLogs = false,
            unsafeDslChecks = true,
            skipDefaultValues = true,
            compileSafety = true,
            aiAssist = false,
        )
    }

    @Test
    fun `jsr330 defaults to true (preserves prior behavior)`() {
        KoinPluginLogger.init(MessageCollector.NONE, userLogs = false, debugLogs = false, aiAssist = false)
        assertTrue(KoinPluginLogger.jsr330Enabled)
    }

    @Test
    fun `jsr330 = false disables the flag`() {
        KoinPluginLogger.init(MessageCollector.NONE, userLogs = false, debugLogs = false, aiAssist = false, jsr330 = false)
        assertFalse(KoinPluginLogger.jsr330Enabled)
    }
}
