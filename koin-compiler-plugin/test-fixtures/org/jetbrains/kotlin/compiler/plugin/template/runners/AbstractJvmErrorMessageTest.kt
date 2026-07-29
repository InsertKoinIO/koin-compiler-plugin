package org.jetbrains.kotlin.compiler.plugin.template.runners

import org.jetbrains.kotlin.compiler.plugin.template.services.CapturedErrors
import java.io.File

/**
 * Test base class that asserts compiler error messages against golden files.
 *
 * Extends the diagnostic test to additionally verify that the actual error messages
 * emitted by the Koin plugin (via MessageCollector) match expected content in
 * `*.errors.txt` golden files.
 *
 * This catches regressions in error message wording and ensures errors are actually
 * emitted (not silently dropped).
 *
 * Golden files are updated when running with `-Pupdate.testdata=true`.
 */
open class AbstractJvmErrorMessageTest : AbstractJvmDiagnosticTest() {

    override fun runTest(filePath: String) {
        CapturedErrors.clear()

        // Run the full compiler pipeline (FIR + IR with RUN_PIPELINE_TILL: BACKEND).
        //
        // We ignore ONLY AssertionError, because that is what the framework's own golden handlers
        // throw (FIR_DUMP, GlobalMetadataInfoHandler → opentest4j AssertionFailedError) — notably
        // for multi-module (`// MODULE:`) files, where GlobalMetadataInfoHandler cannot write the
        // inline `.kt` GENERATED_FIR_TAGS trailer. Those are the DiagnosticTest twin's concern;
        // THIS runner asserts only Koin's diagnostics via the `.errors.txt` golden below, and
        // compilation always completes before those post-module handlers run, so CapturedErrors is
        // fully populated by then.
        //
        // Everything else MUST propagate. This used to `catch (_: Throwable)`, which also swallowed:
        //   - plugin crashes — `IrValidationException` extends IllegalStateException, so an invalid
        //     IR tree produced an EMPTY `.errors.txt` that passed as "no diagnostics" (observed for
        //     real: a hint parameter emitted without its `parent` set);
        //   - PhasedPipelineChecker's IllegalStateException, i.e. the `RUN_PIPELINE_TILL: BACKEND`
        //     assertion — narrowing restores that check for free;
        //   - any Kotlin compiler crash.
        // An empty `_ok` golden could therefore mean "clean" OR "the compiler blew up", which makes
        // every such golden in the suite worthless as evidence.
        try {
            super.runTest(filePath)
        } catch (e: AssertionError) {
            // A MultipleFailuresError (also an AssertionError) can wrap a genuine crash ALONGSIDE
            // the expected golden-handler assertions. Ignoring it wholesale reopens the same hole,
            // so only the assertion-shaped failures may be dropped.
            rethrowIfNotFrameworkAssertion(e)
        }

        // Build actual error output (sorted for deterministic comparison)
        val actualErrors = CapturedErrors.errors.sorted().joinToString("\n")

        // Golden file: same name as .kt but with .errors.txt extension.
        // removeSuffix, not replace: a path containing ".kt" earlier (a directory named `foo.kt`,
        // or a fixture like `a.kt.kt`) would otherwise be rewritten in the middle.
        val errorsFile = File(filePath.removeSuffix(".kt") + ".errors.txt")
        val updateTestData = System.getProperty("update.testdata")?.toBoolean() == true

        if (updateTestData) {
            errorsFile.writeText(actualErrors + "\n")
            return
        }

        if (!errorsFile.exists()) {
            if (actualErrors.isNotEmpty()) {
                errorsFile.writeText(actualErrors + "\n")
                error(
                    "Golden file ${errorsFile.name} did not exist — created it. " +
                    "Review and re-run, or run with -Pupdate.testdata=true."
                )
            }
            // No errors expected, no errors captured — pass
            return
        }

        val expectedErrors = errorsFile.readText().trim()
        if (actualErrors != expectedErrors) {
            error(
                "Error messages mismatch for ${File(filePath).name}.\n\n" +
                "Expected:\n$expectedErrors\n\n" +
                "Actual:\n$actualErrors\n\n" +
                "Run with -Pupdate.testdata=true to update golden files."
            )
        }
    }

    /**
     * Drop framework golden-handler assertions; rethrow anything that isn't one.
     *
     * `MultipleFailuresError` is itself an `AssertionError` but aggregates the individual failures,
     * so a plugin crash reported alongside a FIR-dump mismatch would be invisible if we treated the
     * wrapper as expected. Unwrap it and rethrow the first non-assertion failure.
     */
    private fun rethrowIfNotFrameworkAssertion(e: AssertionError) {
        val failures = (e as? org.opentest4j.MultipleFailuresError)?.failures ?: return
        failures.firstOrNull { it !is AssertionError }?.let { throw it }
    }
}
