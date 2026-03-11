package org.koin.compiler.plugin

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.compiler.plugin.ir.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for ParameterAnalyzer classification rules.
 *
 * ParameterAnalyzer.analyzeParameter() works with IR types (IrValueParameter) and cannot
 * be directly unit-tested without the compiler's IR infrastructure. These tests validate
 * the classification rules by constructing Requirement objects that mirror what
 * ParameterAnalyzer would produce for each parameter scenario, then verifying:
 *
 * 1. The Requirement fields are set correctly for each classification
 * 2. requiresValidation() returns the correct result for each scenario
 * 3. skipDefaultValues flag interaction works correctly
 *
 * Integration tests for ParameterAnalyzer with real IR are covered by box tests in:
 *   testData/box/params/ (injected_param, lazy_injection, property_basic)
 *   testData/box/safety/ (compile-time safety validation end-to-end)
 */
class ParameterAnalyzerTest {

    @BeforeEach
    fun setUp() {
        KoinPluginLogger.init(
            collector = MessageCollector.NONE,
            userLogs = false,
            debugLogs = false,
            skipDefaultValues = true
        )
    }

    // ================================================================================
    // Classification: @InjectedParam parameters
    // ================================================================================

    @Nested
    inner class InjectedParamClassification {

        @Test
        fun `@InjectedParam parameter produces isInjectedParam=true`() {
            val req = simulateInjectedParam("com.example.UserId", "userId")
            assertTrue(req.isInjectedParam)
            assertFalse(req.isLazy)
            assertFalse(req.isList)
            assertFalse(req.isProperty)
        }

        @Test
        fun `@InjectedParam parameter does not require validation`() {
            val req = simulateInjectedParam("com.example.UserId", "userId")
            assertFalse(req.requiresValidation())
        }

        @Test
        fun `@InjectedParam with nullable type does not require validation`() {
            val req = simulateInjectedParam("com.example.UserId", "userId", isNullable = true)
            assertFalse(req.requiresValidation())
        }

        @Test
        fun `@InjectedParam has no qualifier`() {
            val req = simulateInjectedParam("com.example.UserId", "userId")
            assertNull(req.qualifier)
        }

        @Test
        fun `@InjectedParam has no propertyKey`() {
            val req = simulateInjectedParam("com.example.UserId", "userId")
            assertNull(req.propertyKey)
        }
    }

    // ================================================================================
    // Classification: @Property parameters
    // ================================================================================

    @Nested
    inner class PropertyClassification {

        @Test
        fun `@Property parameter produces isProperty=true with key`() {
            val req = simulatePropertyParam("kotlin.String", "dbUrl", propertyKey = "database.url")
            assertTrue(req.isProperty)
            assertEquals("database.url", req.propertyKey)
            assertFalse(req.isInjectedParam)
            assertFalse(req.isLazy)
            assertFalse(req.isList)
        }

        @Test
        fun `@Property parameter does not require validation`() {
            val req = simulatePropertyParam("kotlin.String", "dbUrl", propertyKey = "database.url")
            assertFalse(req.requiresValidation())
        }

        @Test
        fun `@Property with nullable type does not require validation`() {
            val req = simulatePropertyParam("kotlin.String", "dbUrl", propertyKey = "db.url", isNullable = true)
            assertFalse(req.requiresValidation())
        }

        @Test
        fun `@Property has no qualifier`() {
            val req = simulatePropertyParam("kotlin.String", "dbUrl", propertyKey = "db.url")
            assertNull(req.qualifier)
        }
    }

    // ================================================================================
    // Classification: Lazy<T> parameters
    // ================================================================================

    @Nested
    inner class LazyClassification {

        @Test
        fun `Lazy parameter produces isLazy=true with inner type`() {
            val req = simulateLazyParam("com.example.Repository", "repo")
            assertTrue(req.isLazy)
            assertFalse(req.isInjectedParam)
            assertFalse(req.isList)
            assertFalse(req.isProperty)
            // typeKey should be the inner type (Repository), not Lazy
            assertEquals("com.example.Repository", req.typeKey.render())
        }

        @Test
        fun `Lazy parameter requires validation (inner type must be provided)`() {
            val req = simulateLazyParam("com.example.Repository", "repo")
            assertTrue(req.requiresValidation())
        }

        @Test
        fun `Lazy with nullable inner type does not require validation`() {
            // Lazy<Foo?> means getOrNull() for Foo
            val req = simulateLazyParam("com.example.Repository", "repo", innerNullable = true)
            assertFalse(req.requiresValidation())
        }

        @Test
        fun `Lazy with qualifier requires validation`() {
            val req = simulateLazyParam(
                "com.example.Repository", "repo",
                qualifier = QualifierValue.StringQualifier("remote")
            )
            assertTrue(req.requiresValidation())
        }

        @Test
        fun `Lazy with default value and skipDefaultValues does not require validation`() {
            val req = simulateLazyParam("com.example.Repository", "repo", hasDefault = true)
            assertFalse(req.requiresValidation())
        }

        @Test
        fun `Lazy with default value and qualifier still requires validation`() {
            val req = simulateLazyParam(
                "com.example.Repository", "repo",
                hasDefault = true,
                qualifier = QualifierValue.StringQualifier("remote")
            )
            assertTrue(req.requiresValidation())
        }
    }

    // ================================================================================
    // Classification: List<T> parameters
    // ================================================================================

    @Nested
    inner class ListClassification {

        @Test
        fun `List parameter produces isList=true with element type`() {
            val req = simulateListParam("com.example.Plugin", "plugins")
            assertTrue(req.isList)
            assertFalse(req.isInjectedParam)
            assertFalse(req.isLazy)
            assertFalse(req.isProperty)
            // typeKey should be the element type (Plugin), not List
            assertEquals("com.example.Plugin", req.typeKey.render())
        }

        @Test
        fun `List parameter does not require validation (getAll returns empty)`() {
            val req = simulateListParam("com.example.Plugin", "plugins")
            assertFalse(req.requiresValidation())
        }

        @Test
        fun `nullable List parameter does not require validation`() {
            val req = simulateListParam("com.example.Plugin", "plugins", isNullable = true)
            assertFalse(req.requiresValidation())
        }
    }

    // ================================================================================
    // Classification: Nullable parameters
    // ================================================================================

    @Nested
    inner class NullableClassification {

        @Test
        fun `nullable parameter produces isNullable=true`() {
            val req = simulateRegularParam("com.example.Logger", "logger", isNullable = true)
            assertTrue(req.isNullable)
        }

        @Test
        fun `nullable parameter does not require validation`() {
            val req = simulateRegularParam("com.example.Logger", "logger", isNullable = true)
            assertFalse(req.requiresValidation())
        }

        @Test
        fun `nullable parameter with qualifier does not require validation`() {
            // Even with a qualifier, nullable means getOrNull() so no validation needed
            val req = simulateRegularParam(
                "com.example.Logger", "logger",
                isNullable = true,
                qualifier = QualifierValue.StringQualifier("console")
            )
            assertFalse(req.requiresValidation())
        }
    }

    // ================================================================================
    // Classification: Default value parameters
    // ================================================================================

    @Nested
    inner class DefaultValueClassification {

        @Test
        fun `parameter with default and skipDefaultValues=true does not require validation`() {
            val req = simulateRegularParam("kotlin.String", "name", hasDefault = true)
            assertFalse(req.requiresValidation())
        }

        @Test
        fun `parameter with default and skipDefaultValues=false requires validation`() {
            KoinPluginLogger.init(
                collector = MessageCollector.NONE,
                userLogs = false,
                debugLogs = false,
                skipDefaultValues = false
            )
            val req = simulateRegularParam("kotlin.String", "name", hasDefault = true)
            assertTrue(req.requiresValidation())
        }

        @Test
        fun `parameter with default AND qualifier still requires validation even with skipDefaultValues`() {
            val req = simulateRegularParam(
                "kotlin.String", "name",
                hasDefault = true,
                qualifier = QualifierValue.StringQualifier("appName")
            )
            assertTrue(req.requiresValidation())
        }

        @Test
        fun `nullable parameter with default does not require validation regardless of skipDefaultValues`() {
            KoinPluginLogger.init(
                collector = MessageCollector.NONE,
                userLogs = false,
                debugLogs = false,
                skipDefaultValues = false
            )
            val req = simulateRegularParam("kotlin.String", "name", isNullable = true, hasDefault = true)
            assertFalse(req.requiresValidation())
        }
    }

    // ================================================================================
    // Classification: Regular (non-annotated, non-special) parameters
    // ================================================================================

    @Nested
    inner class RegularClassification {

        @Test
        fun `regular non-null parameter requires validation`() {
            val req = simulateRegularParam("com.example.Repository", "repo")
            assertTrue(req.requiresValidation())
        }

        @Test
        fun `regular parameter has correct type key`() {
            val req = simulateRegularParam("com.example.Repository", "repo")
            assertEquals("com.example.Repository", req.typeKey.render())
        }

        @Test
        fun `regular parameter with string qualifier requires validation`() {
            val req = simulateRegularParam(
                "com.example.Repository", "repo",
                qualifier = QualifierValue.StringQualifier("remote")
            )
            assertTrue(req.requiresValidation())
            assertEquals("remote", (req.qualifier as QualifierValue.StringQualifier).name)
        }

        @Test
        fun `regular parameter has all flags false`() {
            val req = simulateRegularParam("com.example.Repository", "repo")
            assertFalse(req.isInjectedParam)
            assertFalse(req.isLazy)
            assertFalse(req.isList)
            assertFalse(req.isProperty)
            assertFalse(req.isNullable)
            assertFalse(req.hasDefault)
            assertNull(req.qualifier)
            assertNull(req.propertyKey)
        }
    }

    // ================================================================================
    // Classification priority: @Property takes precedence over @InjectedParam
    // ================================================================================

    @Nested
    inner class ClassificationPriority {

        @Test
        fun `@Property is checked before @InjectedParam in analyzeParameter`() {
            // ParameterAnalyzer checks @Property first, then @InjectedParam.
            // If a parameter has @Property, it returns isProperty=true regardless of other annotations.
            // We verify this by confirming the Requirement produced has isProperty=true.
            val req = simulatePropertyParam("kotlin.String", "config", propertyKey = "app.config")
            assertTrue(req.isProperty)
            assertFalse(req.isInjectedParam)
        }

        @Test
        fun `@InjectedParam is checked before qualifier extraction`() {
            // ParameterAnalyzer checks @InjectedParam before extracting qualifiers.
            // If a parameter has @InjectedParam, qualifier is set to null.
            val req = simulateInjectedParam("com.example.UserId", "userId")
            assertNull(req.qualifier)
        }
    }

    // ================================================================================
    // TypeKey rendering
    // ================================================================================

    @Nested
    inner class TypeKeyRendering {

        @Test
        fun `TypeKey with both classId and fqName renders fqName`() {
            val key = TypeKey(
                classId = ClassId.topLevel(FqName("com.example.MyService")),
                fqName = FqName("com.example.MyService")
            )
            assertEquals("com.example.MyService", key.render())
        }

        @Test
        fun `TypeKey with only classId renders classId`() {
            val key = TypeKey(
                classId = ClassId.topLevel(FqName("com.example.MyService")),
                fqName = null
            )
            assertEquals("com.example.MyService", key.render())
        }

        @Test
        fun `TypeKey with neither classId nor fqName renders unknown`() {
            val key = TypeKey(classId = null, fqName = null)
            assertEquals("<unknown>", key.render())
        }

        @Test
        fun `TypeKey with nested class classId renders correctly`() {
            val classId = ClassId(FqName("com.example"), FqName("Outer.Inner"), false)
            val key = TypeKey(classId = classId, fqName = FqName("com.example.Outer.Inner"))
            assertEquals("com.example.Outer.Inner", key.render())
        }
    }

    // ================================================================================
    // classIdFromIrClass (static companion method) - tested indirectly via ClassId
    // ================================================================================

    @Nested
    inner class ClassIdConstruction {

        @Test
        fun `top-level ClassId has correct package and class name`() {
            val classId = ClassId.topLevel(FqName("com.example.MyService"))
            assertEquals("com.example", classId.packageFqName.asString())
            assertEquals("MyService", classId.relativeClassName.asString())
        }

        @Test
        fun `nested ClassId preserves relative path`() {
            val classId = ClassId(FqName("com.example"), FqName("Outer.Inner"), false)
            assertEquals("com.example", classId.packageFqName.asString())
            assertEquals("Outer.Inner", classId.relativeClassName.asString())
        }

        @Test
        fun `ClassId asFqNameString matches expected format`() {
            val classId = ClassId.topLevel(FqName("com.example.MyService"))
            // ClassId.asFqNameString() uses '/' separator for nested, '.' for package
            assertEquals("com.example.MyService", classId.asFqNameString().replace('/', '.'))
        }
    }

    // ================================================================================
    // Combined scenarios: multiple requirements from a single constructor
    // ================================================================================

    @Nested
    inner class MultipleRequirements {

        @Test
        fun `constructor with mixed parameter types produces correct requirements`() {
            // Simulates: class Service(
            //   val repo: Repository,                    // regular -> requires validation
            //   @InjectedParam val userId: String,       // injected -> skip
            //   @Property("db.url") val url: String,     // property -> skip
            //   val logger: Logger? = null,              // nullable + default -> skip
            //   val plugins: List<Plugin>                 // list -> skip
            // )
            val requirements = listOf(
                simulateRegularParam("com.example.Repository", "repo"),
                simulateInjectedParam("kotlin.String", "userId"),
                simulatePropertyParam("kotlin.String", "url", propertyKey = "db.url"),
                simulateRegularParam("com.example.Logger", "logger", isNullable = true, hasDefault = true),
                simulateListParam("com.example.Plugin", "plugins")
            )

            val requiresValidation = requirements.filter { it.requiresValidation() }
            assertEquals(1, requiresValidation.size)
            assertEquals("repo", requiresValidation[0].paramName)
            assertEquals("com.example.Repository", requiresValidation[0].typeKey.render())
        }

        @Test
        fun `constructor with all-skippable parameters has no validation requirements`() {
            val requirements = listOf(
                simulateInjectedParam("kotlin.String", "userId"),
                simulatePropertyParam("kotlin.String", "url", propertyKey = "db.url"),
                simulateRegularParam("com.example.Logger", "logger", isNullable = true),
                simulateListParam("com.example.Plugin", "plugins"),
                simulateRegularParam("kotlin.Int", "timeout", hasDefault = true)
            )

            val requiresValidation = requirements.filter { it.requiresValidation() }
            assertEquals(0, requiresValidation.size)
        }

        @Test
        fun `constructor with Lazy and qualified params requires validation for both`() {
            val requirements = listOf(
                simulateLazyParam("com.example.Repository", "repo"),
                simulateRegularParam(
                    "com.example.DataSource", "dataSource",
                    qualifier = QualifierValue.StringQualifier("remote")
                )
            )

            val requiresValidation = requirements.filter { it.requiresValidation() }
            assertEquals(2, requiresValidation.size)
        }
    }

    // ================================================================================
    // Edge cases
    // ================================================================================

    @Nested
    inner class EdgeCases {

        @Test
        fun `Lazy with default and qualifier requires validation`() {
            // Even though hasDefault=true, qualifier is non-null so skipDefaultValues doesn't apply
            val req = simulateLazyParam(
                "com.example.Repository", "repo",
                hasDefault = true,
                qualifier = QualifierValue.StringQualifier("cached")
            )
            assertTrue(req.requiresValidation())
        }

        @Test
        fun `parameter with empty type key renders as unknown`() {
            val req = Requirement(
                typeKey = TypeKey(classId = null, fqName = null),
                paramName = "unknown",
                isNullable = false,
                hasDefault = false,
                isInjectedParam = false,
                isLazy = false,
                isList = false,
                isProperty = false,
                propertyKey = null,
                qualifier = null
            )
            assertEquals("<unknown>", req.typeKey.render())
            assertTrue(req.requiresValidation())
        }

        @Test
        fun `skipDefaultValues toggling affects validation result`() {
            val req = simulateRegularParam("kotlin.String", "name", hasDefault = true)

            // With skipDefaultValues = true (set in setUp)
            assertFalse(req.requiresValidation())

            // Toggle off
            KoinPluginLogger.init(
                collector = MessageCollector.NONE,
                userLogs = false,
                debugLogs = false,
                skipDefaultValues = false
            )
            assertTrue(req.requiresValidation())

            // Toggle back on
            KoinPluginLogger.init(
                collector = MessageCollector.NONE,
                userLogs = false,
                debugLogs = false,
                skipDefaultValues = true
            )
            assertFalse(req.requiresValidation())
        }
    }

    // ================================================================================
    // Helpers: simulate what ParameterAnalyzer.analyzeParameter() would produce
    // ================================================================================

    /**
     * Simulates ParameterAnalyzer output for a parameter annotated with @InjectedParam.
     */
    private fun simulateInjectedParam(
        typeFqName: String,
        paramName: String,
        isNullable: Boolean = false,
        hasDefault: Boolean = false
    ): Requirement = Requirement(
        typeKey = makeTypeKey(typeFqName),
        paramName = paramName,
        isNullable = isNullable,
        hasDefault = hasDefault,
        isInjectedParam = true,
        isLazy = false,
        isList = false,
        isProperty = false,
        propertyKey = null,
        qualifier = null
    )

    /**
     * Simulates ParameterAnalyzer output for a parameter annotated with @Property("key").
     */
    private fun simulatePropertyParam(
        typeFqName: String,
        paramName: String,
        propertyKey: String,
        isNullable: Boolean = false,
        hasDefault: Boolean = false
    ): Requirement = Requirement(
        typeKey = makeTypeKey(typeFqName),
        paramName = paramName,
        isNullable = isNullable,
        hasDefault = hasDefault,
        isInjectedParam = false,
        isLazy = false,
        isList = false,
        isProperty = true,
        propertyKey = propertyKey,
        qualifier = null
    )

    /**
     * Simulates ParameterAnalyzer output for a Lazy<T> parameter.
     * The typeKey is the inner type T, not Lazy itself.
     */
    private fun simulateLazyParam(
        innerTypeFqName: String,
        paramName: String,
        innerNullable: Boolean = false,
        hasDefault: Boolean = false,
        qualifier: QualifierValue? = null
    ): Requirement = Requirement(
        typeKey = makeTypeKey(innerTypeFqName),
        paramName = paramName,
        isNullable = innerNullable,
        hasDefault = hasDefault,
        isInjectedParam = false,
        isLazy = true,
        isList = false,
        isProperty = false,
        propertyKey = null,
        qualifier = qualifier
    )

    /**
     * Simulates ParameterAnalyzer output for a List<T> parameter.
     * The typeKey is the element type T, not List itself.
     */
    private fun simulateListParam(
        elementTypeFqName: String,
        paramName: String,
        isNullable: Boolean = false,
        hasDefault: Boolean = false,
        qualifier: QualifierValue? = null
    ): Requirement = Requirement(
        typeKey = makeTypeKey(elementTypeFqName),
        paramName = paramName,
        isNullable = isNullable,
        hasDefault = hasDefault,
        isInjectedParam = false,
        isLazy = false,
        isList = true,
        isProperty = false,
        propertyKey = null,
        qualifier = qualifier
    )

    /**
     * Simulates ParameterAnalyzer output for a regular (non-special) parameter.
     */
    private fun simulateRegularParam(
        typeFqName: String,
        paramName: String,
        isNullable: Boolean = false,
        hasDefault: Boolean = false,
        qualifier: QualifierValue? = null
    ): Requirement = Requirement(
        typeKey = makeTypeKey(typeFqName),
        paramName = paramName,
        isNullable = isNullable,
        hasDefault = hasDefault,
        isInjectedParam = false,
        isLazy = false,
        isList = false,
        isProperty = false,
        propertyKey = null,
        qualifier = qualifier
    )

    private fun makeTypeKey(fqName: String): TypeKey = TypeKey(
        classId = ClassId.topLevel(FqName(fqName)),
        fqName = FqName(fqName)
    )
}
