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

        // Run the full compiler pipeline (FIR + IR with RUN_PIPELINE_TILL: BACKEND)
        // Diagnostic tests handle compilation errors gracefully
        try {
            super.runTest(filePath)
        } catch (_: Exception) {
            // Compilation errors are expected in these tests —
            // the framework may throw on ERROR-severity messages
        }

        // Build actual error output (sorted for deterministic comparison)
        val actualErrors = CapturedErrors.errors.sorted().joinToString("\n")

        // Golden file: same name as .kt but with .errors.txt extension
        val errorsFile = File(filePath.replace(".kt", ".errors.txt"))
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
}
