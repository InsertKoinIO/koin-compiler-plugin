package org.koin.compiler.plugin

import org.junit.jupiter.api.Test
import org.koin.compiler.plugin.ir.QualifierValue
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertIs

/**
 * Unit tests for QualifierValue sealed class and its behavior.
 *
 * The QualifierExtractor class methods (extractFromDeclaration, extractFromParameter,
 * findCustomQualifierAnnotation, createQualifierCall, discoverQualifierHints) all operate
 * on IR types (IrClass, IrValueParameter, IrPluginContext) and are covered by box tests:
 *
 * - testData/box/qualifiers/named_on_class.kt       — @Named("x") on class definitions
 * - testData/box/qualifiers/named_on_parameter.kt   — @Named("x") on constructor parameters
 * - testData/box/qualifiers/qualifier_type.kt        — @Qualifier(Type::class) on class and parameter
 *
 * These unit tests focus on the QualifierValue data model which is testable without IR infrastructure.
 */
class QualifierExtractorTest {

    // ================================================================================
    // QualifierValue.StringQualifier tests
    // ================================================================================

    @Test
    fun `StringQualifier holds name`() {
        val qualifier = QualifierValue.StringQualifier("prod")
        assertEquals("prod", qualifier.name)
    }

    @Test
    fun `StringQualifier debugString includes name in Named format`() {
        val qualifier = QualifierValue.StringQualifier("my-qualifier")
        assertEquals("@Named(\"my-qualifier\")", qualifier.debugString())
    }

    @Test
    fun `StringQualifier equality by name`() {
        val a = QualifierValue.StringQualifier("prod")
        val b = QualifierValue.StringQualifier("prod")
        assertEquals(a, b)
    }

    @Test
    fun `StringQualifier inequality for different names`() {
        val a = QualifierValue.StringQualifier("prod")
        val b = QualifierValue.StringQualifier("test")
        assertNotEquals(a, b)
    }

    @Test
    fun `StringQualifier with empty name`() {
        val qualifier = QualifierValue.StringQualifier("")
        assertEquals("", qualifier.name)
        assertEquals("@Named(\"\")", qualifier.debugString())
    }

    @Test
    fun `StringQualifier is a QualifierValue`() {
        val qualifier: QualifierValue = QualifierValue.StringQualifier("x")
        assertIs<QualifierValue.StringQualifier>(qualifier)
    }

    // ================================================================================
    // QualifierValue.TypeQualifier tests
    // ================================================================================

    // TypeQualifier holds an IrClass reference, so its construction and debugString
    // require IR infrastructure. The TypeQualifier data class behavior (equality based
    // on IrClass identity) is validated by the box test:
    //   testData/box/qualifiers/qualifier_type.kt

    // ================================================================================
    // QualifierValue sealed class exhaustiveness
    // ================================================================================

    @Test
    fun `when expression covers all QualifierValue subtypes`() {
        val stringQualifier: QualifierValue = QualifierValue.StringQualifier("name")
        // Verify the sealed class can be pattern-matched (compile-time check)
        val result = when (stringQualifier) {
            is QualifierValue.StringQualifier -> "string"
            is QualifierValue.TypeQualifier -> "type"
        }
        assertEquals("string", result)
    }

    // ================================================================================
    // QualifierValue matching semantics (used by BindingRegistry)
    // ================================================================================

    @Test
    fun `StringQualifier with same name matches as data class`() {
        val required = QualifierValue.StringQualifier("prod")
        val provided = QualifierValue.StringQualifier("prod")
        assertEquals(required, provided)
    }

    @Test
    fun `StringQualifier with different names do not match`() {
        val required = QualifierValue.StringQualifier("prod")
        val provided = QualifierValue.StringQualifier("dev")
        assertNotEquals(required, provided)
    }

    @Test
    fun `StringQualifier hashCode is consistent for equal instances`() {
        val a = QualifierValue.StringQualifier("qualifier")
        val b = QualifierValue.StringQualifier("qualifier")
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `StringQualifier can be used in sets for lookup`() {
        val set = setOf(
            QualifierValue.StringQualifier("a"),
            QualifierValue.StringQualifier("b"),
            QualifierValue.StringQualifier("a") // duplicate
        )
        assertEquals(2, set.size)
    }

    @Test
    fun `StringQualifier debugString with special characters`() {
        val qualifier = QualifierValue.StringQualifier("com.example.NiaDispatchers.IO")
        assertEquals("@Named(\"com.example.NiaDispatchers.IO\")", qualifier.debugString())
    }

    @Test
    fun `null QualifierValue is distinct from any StringQualifier`() {
        val qualifier: QualifierValue? = null
        val stringQualifier: QualifierValue? = QualifierValue.StringQualifier("x")
        assertNotEquals(qualifier, stringQualifier)
    }
}
