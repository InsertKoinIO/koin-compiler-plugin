package org.koin.compiler.plugin

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the `logSeverity`/`versionCheckSeverity` knobs (issue #73): the plugin's own
 * informational output must be configurable to a build-safe severity under
 * `allWarningsAsErrors`, while real diagnostics stay untouched.
 *
 * `@Isolated` for the same reason as [KoinDiagnosticTest]: [KoinPluginLogger] is a
 * process-wide singleton.
 */
@Isolated
class KoinLogSeverityTest {

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

    private class Recorder : MessageCollector {
        data class Report(val severity: CompilerMessageSeverity, val message: String, val location: CompilerMessageSourceLocation?)
        val reports = mutableListOf<Report>()
        override fun clear() = reports.clear()
        override fun hasErrors(): Boolean = reports.any { it.severity == CompilerMessageSeverity.ERROR }
        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
            reports += Report(severity, message, location)
        }
    }

    @Test
    fun `parse defaults to WARNING for unrecognized or missing values`() {
        assertEquals(KoinLogSeverity.WARNING, KoinLogSeverity.parse(null))
        assertEquals(KoinLogSeverity.WARNING, KoinLogSeverity.parse(""))
        assertEquals(KoinLogSeverity.WARNING, KoinLogSeverity.parse("nonsense"))
        assertEquals(KoinLogSeverity.WARNING, KoinLogSeverity.parse("warning"))
        assertEquals(KoinLogSeverity.INFO, KoinLogSeverity.parse("info"))
        assertEquals(KoinLogSeverity.INFO, KoinLogSeverity.parse("INFO"))
    }

    @Test
    fun `user, debug, userFir, debugFir default to WARNING (preserves prior behavior)`() {
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = true, debugLogs = true, aiAssist = false)

        KoinPluginLogger.user { "u" }
        KoinPluginLogger.debug { "d" }
        KoinPluginLogger.userFir { "uf" }
        KoinPluginLogger.debugFir { "df" }
        KoinPluginLogger.warn("w")

        assertEquals(5, rec.reports.size)
        rec.reports.forEach {
            assertEquals(CompilerMessageSeverity.WARNING, it.severity, "expected WARNING for: ${it.message}")
        }
    }

    @Test
    fun `user, debug, userFir, debugFir, warn downgrade to INFO when logSeverity is info`() {
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = true, debugLogs = true, aiAssist = false, logSeverity = KoinLogSeverity.INFO)

        KoinPluginLogger.user { "u" }
        KoinPluginLogger.debug { "d" }
        KoinPluginLogger.userFir { "uf" }
        KoinPluginLogger.debugFir { "df" }
        KoinPluginLogger.warn("w")

        assertEquals(5, rec.reports.size)
        rec.reports.forEach {
            assertEquals(CompilerMessageSeverity.INFO, it.severity, "expected INFO for: ${it.message}")
        }
    }

    @Test
    fun `version-check severity defaults to STRONG_WARNING, independent of logSeverity`() {
        KoinPluginLogger.init(MessageCollector.NONE, userLogs = false, debugLogs = false, aiAssist = false, logSeverity = KoinLogSeverity.INFO)
        assertEquals(CompilerMessageSeverity.STRONG_WARNING, KoinPluginLogger.effectiveVersionCheckCompilerSeverity)
    }

    @Test
    fun `version-check severity downgrades to INFO only when versionCheckSeverity is info`() {
        KoinPluginLogger.init(
            MessageCollector.NONE, userLogs = false, debugLogs = false, aiAssist = false,
            logSeverity = KoinLogSeverity.WARNING, versionCheckSeverity = KoinLogSeverity.INFO,
        )
        assertEquals(CompilerMessageSeverity.INFO, KoinPluginLogger.effectiveVersionCheckCompilerSeverity)
    }

    @Test
    fun `real diagnostics stay at their own severity regardless of logSeverity`() {
        // KOIN-W001/P001/M001 (WARNING-severity diagnostics) and KOIN-D001 (ERROR) must not be
        // affected by the informational-output severity setting — only `user`/`debug`/`userFir`/
        // `debugFir`/`warn` are in scope for #73.
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = false, logSeverity = KoinLogSeverity.INFO)

        KoinPluginLogger.report(KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null))
        KoinPluginLogger.report(KoinDiagnostic.UnreachableModule("m", listOf("T")))
        KoinPluginLogger.report(KoinDiagnostic.MissingPropertyValue("k", "D", "M"))
        KoinPluginLogger.report(KoinDiagnostic.MonitorNoSdk())

        assertEquals(4, rec.reports.size)
        assertEquals(CompilerMessageSeverity.ERROR, rec.reports[0].severity, "KOIN-D001 must stay ERROR")
        assertEquals(CompilerMessageSeverity.WARNING, rec.reports[1].severity, "KOIN-W001 must stay WARNING")
        assertEquals(CompilerMessageSeverity.WARNING, rec.reports[2].severity, "KOIN-P001 must stay WARNING")
        assertEquals(CompilerMessageSeverity.WARNING, rec.reports[3].severity, "KOIN-M001 must stay WARNING")
    }

    @Test
    fun `flushAiAssistCta severity is unaffected by logSeverity`() {
        // The CTA's severity mirrors the highest real diagnostic severity seen, not logSeverity.
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = true, logSeverity = KoinLogSeverity.INFO)

        KoinPluginLogger.report(KoinDiagnostic.MissingPropertyValue("k", "D", "M"))
        KoinPluginLogger.flushAiAssistCta()

        val cta = rec.reports.last()
        assertEquals(CompilerMessageSeverity.WARNING, cta.severity)
        assertTrue("Fix with AI" in cta.message)
    }
}
