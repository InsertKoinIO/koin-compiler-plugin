package org.koin.compiler.plugin

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Unit tests for KoinConfigurationRegistry - tests the label-based module storage.
 *
 * Note: Tests use unique module names with UUIDs because System properties persist
 * across tests in the same JVM process. Tests verify containment rather than exact sets.
 */
class KoinConfigurationRegistryTest {

    private val testId = System.nanoTime().toString()

    @BeforeEach
    fun setUp() {
        // Clear before each test, but note that other tests may have added entries
        KoinConfigurationRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        KoinConfigurationRegistry.clear()
    }

    @Test
    fun `register module with default label`() {
        val moduleName = "com.example.DefaultModule_$testId"
        KoinConfigurationRegistry.registerModule(moduleName, emptyList())

        val modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(emptyList())
        assertTrue(modules.contains(moduleName), "Module should be found with default label")
    }

    @Test
    fun `register module with explicit default label`() {
        val moduleName = "com.example.ExplicitDefault_$testId"
        KoinConfigurationRegistry.registerModule(moduleName, listOf("default"))

        val modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf("default"))
        assertTrue(modules.contains(moduleName), "Module should be found with explicit default label")
    }

    @Test
    fun `register module with single label`() {
        val moduleName = "com.example.SingleLabel_$testId"
        val uniqueLabel = "singleLabel_$testId"
        KoinConfigurationRegistry.registerModule(moduleName, listOf(uniqueLabel))

        // Should find with specific label
        val labeledModules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(uniqueLabel))
        assertTrue(labeledModules.contains(moduleName), "Module should be found with specific label")

        // Should NOT find with unrelated label
        val unrelatedModules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf("unrelated_$testId"))
        assertTrue(!unrelatedModules.contains(moduleName), "Module should NOT be found with unrelated label")
    }

    @Test
    fun `register module with multiple labels`() {
        val moduleName = "com.example.MultiLabel_$testId"
        val label1 = "multiLabel1_$testId"
        val label2 = "multiLabel2_$testId"
        KoinConfigurationRegistry.registerModule(moduleName, listOf(label1, label2))

        // Should find with first label
        val label1Modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(label1))
        assertTrue(label1Modules.contains(moduleName), "Module should be found with label1")

        // Should find with second label
        val label2Modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(label2))
        assertTrue(label2Modules.contains(moduleName), "Module should be found with label2")

        // Should NOT find with unrelated label
        val unrelatedModules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf("unrelated_$testId"))
        assertTrue(!unrelatedModules.contains(moduleName), "Module should NOT be found with unrelated label")
    }

    @Test
    fun `query with multiple labels returns union`() {
        val module1 = "com.example.Union1_$testId"
        val module2 = "com.example.Union2_$testId"
        val label1 = "union1_$testId"
        val label2 = "union2_$testId"

        KoinConfigurationRegistry.registerModule(module1, listOf(label1))
        KoinConfigurationRegistry.registerModule(module2, listOf(label2))

        // Query both labels - should get both modules
        val modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(label1, label2))
        assertTrue(modules.contains(module1), "Union should contain module1")
        assertTrue(modules.contains(module2), "Union should contain module2")
    }

    @Test
    fun `getAllModuleClassNames returns registered modules`() {
        val module1 = "com.example.All1_$testId"
        val module2 = "com.example.All2_$testId"
        val module3 = "com.example.All3_$testId"

        KoinConfigurationRegistry.registerModule(module1, emptyList())
        KoinConfigurationRegistry.registerModule(module2, listOf("label_$testId"))
        KoinConfigurationRegistry.registerModule(module3, listOf("label1_$testId", "label2_$testId"))

        val allModules = KoinConfigurationRegistry.getAllModuleClassNames()
        assertTrue(allModules.contains(module1), "Should contain module1")
        assertTrue(allModules.contains(module2), "Should contain module2")
        assertTrue(allModules.contains(module3), "Should contain module3")
    }

    @Test
    fun `getAllLabels returns registered labels`() {
        val uniqueLabel1 = "allLabels1_$testId"
        val uniqueLabel2 = "allLabels2_$testId"

        KoinConfigurationRegistry.registerModule("com.example.Labels1_$testId", emptyList()) // default
        KoinConfigurationRegistry.registerModule("com.example.Labels2_$testId", listOf(uniqueLabel1))
        KoinConfigurationRegistry.registerModule("com.example.Labels3_$testId", listOf(uniqueLabel1, uniqueLabel2))

        val labels = KoinConfigurationRegistry.getAllLabels()
        assertTrue(labels.contains("default"), "Should contain 'default' label")
        assertTrue(labels.contains(uniqueLabel1), "Should contain uniqueLabel1")
        assertTrue(labels.contains(uniqueLabel2), "Should contain uniqueLabel2")
    }

    @Test
    fun `registerLocalModule uses default label when empty`() {
        val moduleName = "com.example.LocalDefault_$testId"
        KoinConfigurationRegistry.registerLocalModule(moduleName)

        val modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf("default"))
        assertTrue(modules.contains(moduleName), "Local module should be found with default label")
    }

    @Test
    fun `registerLocalModule with labels`() {
        val moduleName = "com.example.LocalLabeled_$testId"
        val label1 = "localLabel1_$testId"
        val label2 = "localLabel2_$testId"

        KoinConfigurationRegistry.registerLocalModule(moduleName, listOf(label1, label2))

        val label1Modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(label1))
        assertTrue(label1Modules.contains(moduleName), "Local module should be found with label1")

        val label2Modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(label2))
        assertTrue(label2Modules.contains(moduleName), "Local module should be found with label2")
    }

    @Test
    fun `registerJarModule with specific label`() {
        val moduleName = "com.example.JarLabeled_$testId"
        val uniqueLabel = "jarLabel_$testId"

        KoinConfigurationRegistry.registerJarModule(moduleName, uniqueLabel)

        val modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(uniqueLabel))
        assertTrue(modules.contains(moduleName), "JAR module should be found with specific label")
    }

    @Test
    fun `duplicate registrations are handled correctly`() {
        val moduleName = "com.example.Duplicate_$testId"
        val uniqueLabel = "duplicateLabel_$testId"

        KoinConfigurationRegistry.registerModule(moduleName, listOf(uniqueLabel))
        KoinConfigurationRegistry.registerModule(moduleName, listOf(uniqueLabel))

        val modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(uniqueLabel))
        assertTrue(modules.contains(moduleName), "Module should be found")
        // Count how many times it appears (should be 1 due to Set semantics)
        assertTrue(modules.count { it == moduleName } == 1, "Module should appear only once")
    }

    @Test
    fun `same module with different labels appears under both`() {
        val moduleName = "com.example.MultiReg_$testId"
        val label1 = "multiReg1_$testId"
        val label2 = "multiReg2_$testId"

        // Register same module under different labels in separate calls
        KoinConfigurationRegistry.registerModule(moduleName, listOf(label1))
        KoinConfigurationRegistry.registerModule(moduleName, listOf(label2))

        val label1Modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(label1))
        assertTrue(label1Modules.contains(moduleName), "Module should be found with label1")

        val label2Modules = KoinConfigurationRegistry.getModuleClassNamesForLabels(listOf(label2))
        assertTrue(label2Modules.contains(moduleName), "Module should be found with label2")
    }
}
