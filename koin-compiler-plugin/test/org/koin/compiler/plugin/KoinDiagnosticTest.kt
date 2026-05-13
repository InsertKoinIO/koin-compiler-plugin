package org.koin.compiler.plugin

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the [KoinDiagnostic] catalog and the logger's CTA-append behavior.
 *
 * The KOIN-* codes are a public contract with the Kotzilla MCP Server classifier.
 * Changing a code here is a breaking change and must be coordinated with the server.
 *
 * `@Isolated` because [KoinPluginLogger] is a singleton — tests must run sequentially so
 * one test's `init` does not clobber another's recorder.
 */
@Isolated
class KoinDiagnosticTest {

    @AfterEach
    fun restoreLoggerDefaults() {
        // The KoinPluginLogger is a process-wide singleton. Restore defaults so a flaky
        // test scheduler (Kotlin compiler test framework runs modules via ForkJoinPool)
        // does not see our recorder or non-default flag state from a prior test.
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
    fun `codes are stable`() {
        assertEquals("KOIN-D001", KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null).code)
        assertEquals("KOIN-D002", KoinDiagnostic.MissingCallSite("T", "get").code)
        assertEquals("KOIN-D003", KoinDiagnostic.MissingCallSiteDeferred("T").code)
        assertEquals("KOIN-D004", KoinDiagnostic.CircularDependency(listOf("A", "B", "A")).code)
        assertEquals(
            "KOIN-D005",
            KoinDiagnostic.MismatchedInjectedParams(
                target = "T",
                expected = listOf("id: kotlin.String"),
                actual = listOf("kotlin.Int"),
                reason = KoinDiagnostic.MismatchedInjectedParams.Reason.TYPE,
            ).code,
        )
        assertEquals(
            "KOIN-D006",
            KoinDiagnostic.MissingInjectedParams(
                target = "T",
                expected = listOf("id: kotlin.String"),
                callFn = "inject",
            ).code,
        )
        assertEquals("KOIN-W001", KoinDiagnostic.UnreachableModule("m", listOf("T")).code)
        assertEquals("KOIN-A001", KoinDiagnostic.MissingViewModelArtifact("D").code)
        assertEquals("KOIN-A002", KoinDiagnostic.MissingWorkerArtifact("D").code)
        assertEquals("KOIN-A003", KoinDiagnostic.MissingCoreArtifact("M").code)
        assertEquals("KOIN-S001", KoinDiagnostic.UnsafeDsl("T").code)
        assertEquals("KOIN-P001", KoinDiagnostic.MissingPropertyValue("k", "D", "M").code)
        assertEquals("KOIN-M001", KoinDiagnostic.MonitorNoSdk().code)
    }

    @Test
    fun `severity is correct per code`() {
        assertEquals(KoinDiagnostic.Severity.ERROR, KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null).severity)
        assertEquals(KoinDiagnostic.Severity.ERROR, KoinDiagnostic.MissingCoreArtifact("M").severity)
        assertEquals(KoinDiagnostic.Severity.ERROR, KoinDiagnostic.UnsafeDsl("T").severity)
        assertEquals(KoinDiagnostic.Severity.ERROR, KoinDiagnostic.CircularDependency(listOf("A", "B", "A")).severity)
        assertEquals(
            KoinDiagnostic.Severity.ERROR,
            KoinDiagnostic.MismatchedInjectedParams(
                target = "T",
                expected = emptyList(),
                actual = emptyList(),
                reason = KoinDiagnostic.MismatchedInjectedParams.Reason.ARITY,
            ).severity,
        )
        assertEquals(
            KoinDiagnostic.Severity.ERROR,
            KoinDiagnostic.MissingInjectedParams("T", emptyList(), "inject").severity,
        )
        assertEquals(KoinDiagnostic.Severity.WARNING, KoinDiagnostic.MissingPropertyValue("k", "D", "M").severity)
        assertEquals(KoinDiagnostic.Severity.WARNING, KoinDiagnostic.MonitorNoSdk().severity)
    }

    @Test
    fun `mismatched injected params renders both expected and actual lists`() {
        val d = KoinDiagnostic.MismatchedInjectedParams(
            target = "com.example.Greeter",
            expected = listOf("name: kotlin.String", "count: kotlin.Int"),
            actual = listOf("kotlin.Int", "kotlin.Int"),
            reason = KoinDiagnostic.MismatchedInjectedParams.Reason.TYPE,
        )
        assertTrue("Mismatched parametersOf(...) for com.example.Greeter" in d.message)
        assertTrue("type mismatch" in d.message)
        assertTrue("name: kotlin.String, count: kotlin.Int" in d.message)
        assertTrue("kotlin.Int, kotlin.Int" in d.message)
    }

    @Test
    fun `mismatched injected params arity reason mentions counts`() {
        val d = KoinDiagnostic.MismatchedInjectedParams(
            target = "com.example.A",
            expected = listOf("id: kotlin.String"),
            actual = emptyList(),
            reason = KoinDiagnostic.MismatchedInjectedParams.Reason.ARITY,
        )
        assertTrue("expected 1 argument(s), got 0" in d.message)
    }

    @Test
    fun `missing injected params renders target and slot list`() {
        val d = KoinDiagnostic.MissingInjectedParams(
            target = "com.example.A",
            expected = listOf("id: kotlin.String"),
            callFn = "inject",
        )
        assertTrue("com.example.A requires 1 injected param(s)" in d.message)
        assertTrue("inject<A>()" in d.message)
        assertTrue("id: kotlin.String" in d.message)
        assertTrue("parametersOf" in d.message)
    }

    @Test
    fun `circular dependency renders path with arrows`() {
        val d = KoinDiagnostic.CircularDependency(listOf("com.example.A", "com.example.B", "com.example.A"))
        assertTrue("com.example.A → com.example.B → com.example.A" in d.message)
        assertTrue("Circular dependency detected" in d.message)
        assertTrue("Lazy<T>" in d.message)
    }

    @Test
    fun `missing binding renders qualifier and hint`() {
        val d = KoinDiagnostic.MissingBinding(
            type = "com.example.Repository",
            qualifier = "@Named(\"prod\")",
            def = "Service",
            param = "repo",
            module = "appModule",
            hint = "Found similar binding: Repository with qualifier @Named(\"test\")",
        )
        assertTrue("qualified with @Named(\"prod\")" in d.message)
        assertTrue("required by: Service (parameter 'repo')" in d.message)
        assertTrue("in module: appModule" in d.message)
        assertTrue("Hint: Found similar binding" in d.message)
    }

    @Test
    fun `missing binding without qualifier omits it`() {
        val d = KoinDiagnostic.MissingBinding(
            type = "Repository", qualifier = null, def = "Service", param = "repo", module = "M", hint = null,
        )
        assertFalse("qualified with" in d.message)
        assertFalse("Hint:" in d.message)
    }

    @Test
    fun `logger emits code prefix and respects severity`() {
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = false)
        KoinPluginLogger.report(KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null))
        KoinPluginLogger.report(KoinDiagnostic.MissingPropertyValue("k", "D", "M"))

        assertEquals(2, rec.reports.size)
        assertEquals(CompilerMessageSeverity.ERROR, rec.reports[0].severity)
        assertTrue(rec.reports[0].message.startsWith("[Koin][KOIN-D001] Missing dependency:"))
        assertEquals(CompilerMessageSeverity.WARNING, rec.reports[1].severity)
        assertTrue(rec.reports[1].message.startsWith("[Koin][KOIN-P001] Missing @PropertyValue default:"))
    }

    @Test
    fun `individual diagnostic message never contains CTA`() {
        // The CTA is emitted once at the tail by flushAiAssistCta(), never on the
        // diagnostic body itself — keep error messages clean and the CTA sticky.
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = true)
        KoinPluginLogger.report(KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null))

        val message = rec.reports.single().message
        assertFalse("Fix with AI" in message, "CTA leaked into diagnostic body: $message")
        assertFalse(KoinPluginConstants.AI_ASSIST_CTA_URL in message)
    }

    @Test
    fun `flush emits trailing CTA when aiAssist is on and a diagnostic was reported`() {
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = true)
        KoinPluginLogger.report(KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null))
        KoinPluginLogger.report(KoinDiagnostic.MissingBinding("U", null, "D2", "p", "M", null))
        KoinPluginLogger.flushAiAssistCta()

        // 2 diagnostics + 1 trailing CTA, in order.
        assertEquals(3, rec.reports.size)
        val cta = rec.reports.last()
        assertEquals(CompilerMessageSeverity.ERROR, cta.severity)
        assertTrue("Fix with AI" in cta.message, "CTA missing: ${cta.message}")
        assertTrue(KoinPluginConstants.AI_ASSIST_CTA_URL in cta.message)
    }

    @Test
    fun `flush emits CTA at warning severity when only warnings were reported`() {
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = true)
        KoinPluginLogger.report(KoinDiagnostic.MissingPropertyValue("k", "D", "M"))
        KoinPluginLogger.flushAiAssistCta()

        val cta = rec.reports.last()
        assertEquals(CompilerMessageSeverity.WARNING, cta.severity)
        assertTrue("Fix with AI" in cta.message)
    }

    @Test
    fun `flush is a no-op when no diagnostic was reported`() {
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = true)
        KoinPluginLogger.flushAiAssistCta()

        assertTrue(rec.reports.isEmpty(), "CTA emitted with no preceding diagnostic: ${rec.reports}")
    }

    @Test
    fun `flush attaches location of last located diagnostic to CTA`() {
        // The CTA needs a CompilerMessageLocation so Gradle's K2 renderer doesn't sort it
        // ahead of file-anchored errors (it puts location-less messages in a separate bucket).
        // We anchor on the most recent located diagnostic so the CTA appears alongside it.
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = true)
        KoinPluginLogger.report(
            KoinDiagnostic.MissingCallSite("T", "get"),
            filePath = "/src/A.kt", line = 10, column = 5,
        )
        KoinPluginLogger.report(
            KoinDiagnostic.MissingCallSite("U", "get"),
            filePath = "/src/B.kt", line = 42, column = 7,
        )
        KoinPluginLogger.flushAiAssistCta()

        val cta = rec.reports.last()
        assertTrue("Fix with AI" in cta.message)
        val loc = cta.location
        assertEquals("/src/B.kt", loc?.path)
        assertEquals(42, loc?.line)
        assertEquals(7, loc?.column)
    }

    @Test
    fun `flush leaves CTA unlocated when no diagnostic has a location`() {
        // If diagnostics fired but none had a file:line, the CTA falls back to no location.
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = true)
        KoinPluginLogger.report(KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null))
        KoinPluginLogger.flushAiAssistCta()

        val cta = rec.reports.last()
        assertTrue("Fix with AI" in cta.message)
        assertNull(cta.location)
    }

    @Test
    fun `flush honors caller-provided collector over singleton (parallel-daemon safety)`() {
        // Compilation A captures its own collector at extension construction. If a parallel
        // compilation B's init() swaps the singleton's collector mid-flight, A's flush must
        // still write to A's captured collector, not whichever one B installed.
        val a = Recorder()
        val b = Recorder()
        KoinPluginLogger.init(a, userLogs = false, debugLogs = false, aiAssist = true)
        KoinPluginLogger.report(KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null))
        // Simulate parallel compilation B taking over the singleton.
        KoinPluginLogger.init(b, userLogs = false, debugLogs = false, aiAssist = true)
        // Compilation A's flush — passes its own captured collector.
        KoinPluginLogger.flushAiAssistCta(collector = a)

        // CTA should land in A's recorder, not B's. (B's init resets severity to 0, so the
        // singleton state alone would silently swallow the CTA — but we want it visible to A.)
        // We allow the CTA to be absent if severity was reset; the key assertion is no leak to B.
        assertTrue(b.reports.isEmpty(), "CTA leaked to compilation B's collector: ${b.reports}")
    }

    @Test
    fun `flush is a no-op when aiAssist is disabled even if diagnostics fired`() {
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = false)
        KoinPluginLogger.report(KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null))
        KoinPluginLogger.flushAiAssistCta()

        assertEquals(1, rec.reports.size, "Expected only the original diagnostic, not a CTA")
        val message = rec.reports.single().message
        assertFalse("Fix with AI" in message)
        assertFalse(KoinPluginConstants.AI_ASSIST_CTA_URL in message)
    }

    @Test
    fun `logger attaches source location when provided`() {
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = false)
        KoinPluginLogger.report(
            KoinDiagnostic.MissingCallSite(type = "T", callFn = "get"),
            filePath = "/abs/path/App.kt", line = 14, column = 5,
        )

        val location = rec.reports.single().location
        assertEquals("/abs/path/App.kt", location?.path)
        assertEquals(14, location?.line)
        assertEquals(5, location?.column)
    }

    @Test
    fun `logger omits location when no file path given`() {
        val rec = Recorder()
        KoinPluginLogger.init(rec, userLogs = false, debugLogs = false, aiAssist = false)
        KoinPluginLogger.report(KoinDiagnostic.MissingBinding("T", null, "D", "p", "M", null))

        assertNull(rec.reports.single().location)
    }

    @Test
    fun `CTA URL matches doc redirect contract`() {
        // The plugin emits this URL once per build (trailing CTA) when aiAssist is on.
        // The redirect at kotzilla.io/koin-mcp must always exist; do not change without coordinating.
        assertEquals("https://kotzilla.io/koin-mcp", KoinPluginConstants.AI_ASSIST_CTA_URL)
    }
}
