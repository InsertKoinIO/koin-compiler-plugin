package org.jetbrains.kotlin.compiler.plugin.template

import org.jetbrains.kotlin.compiler.plugin.template.runners.AbstractJvmBoxTest
import org.jetbrains.kotlin.compiler.plugin.template.runners.AbstractJvmDiagnosticTest
import org.jetbrains.kotlin.compiler.plugin.template.runners.AbstractJvmErrorMessageTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

fun main(args: Array<String>) {
    generateTestGroupSuiteWithJUnit5(args) {
        testGroup(testDataRoot = "koin-compiler-plugin/testData", testsRoot = "koin-compiler-plugin/test-gen") {
            testClass<AbstractJvmDiagnosticTest> {
                model("diagnostics")
            }

            testClass<AbstractJvmErrorMessageTest> {
                model("diagnostics")

                // Cross-module (`// MODULE:`) diagnostic tests: registered ONLY under the
                // error-message runner (which asserts `.errors.txt` and tolerates the framework's
                // FIR-dump/metadata golden handlers — see AbstractJvmErrorMessageTest). NOT
                // registered under AbstractJvmDiagnosticTest because its GlobalMetadataInfoHandler
                // can't handle multi-module files. This is the vehicle for A3 cross-module
                // compile-safety diagnostics (ExternalFunctionDef only exists across compilation
                // boundaries). Same testClass block as `diagnostics` — the generator emits one Java
                // file per testClass type, so a second `model(...)` becomes another nested class.
                model("crossmodule")
            }

            testClass<AbstractJvmBoxTest> {
                model("box")
            }
        }
    }
}
