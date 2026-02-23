package org.koin.compiler.plugin

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.compiler.plugin.ir.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for compile-time safety validation logic.
 *
 * Tests the Requirement.requiresValidation() rules that determine
 * which parameters need a matching provider.
 */
class BindingRegistryTest {

    @BeforeEach
    fun setUp() {
        // Ensure skipDefaultValues is enabled (default)
        // This is set in KoinPluginLogger, which is a singleton
    }

    // ================================================================================
    // Requirement.requiresValidation() tests
    // ================================================================================

    @Test
    fun `regular non-null parameter requires validation`() {
        val req = makeRequirement()
        assertTrue(req.requiresValidation())
    }

    @Test
    fun `nullable parameter does not require validation`() {
        val req = makeRequirement(isNullable = true)
        assertFalse(req.requiresValidation())
    }

    @Test
    fun `injected param does not require validation`() {
        val req = makeRequirement(isInjectedParam = true)
        assertFalse(req.requiresValidation())
    }

    @Test
    fun `list parameter does not require validation`() {
        val req = makeRequirement(isList = true)
        assertFalse(req.requiresValidation())
    }

    @Test
    fun `property parameter does not require validation`() {
        val req = makeRequirement(isProperty = true, propertyKey = "my.key")
        assertFalse(req.requiresValidation())
    }

    @Test
    fun `parameter with default value does not require validation when skipDefaultValues enabled`() {
        // KoinPluginLogger.skipDefaultValuesEnabled defaults to true
        val req = makeRequirement(hasDefault = true)
        assertFalse(req.requiresValidation())
    }

    @Test
    fun `parameter with default value AND qualifier still requires validation`() {
        val req = makeRequirement(
            hasDefault = true,
            qualifier = QualifierValue.StringQualifier("named")
        )
        // Even with skipDefaultValues, a qualified param must be validated
        assertTrue(req.requiresValidation())
    }

    @Test
    fun `lazy parameter requires validation`() {
        // Lazy<T> still needs T to be provided
        val req = makeRequirement(isLazy = true)
        assertTrue(req.requiresValidation())
    }

    // ================================================================================
    // TypeKey tests
    // ================================================================================

    @Test
    fun `TypeKey render with fqName`() {
        val key = TypeKey(
            classId = ClassId.topLevel(FqName("com.example.MyClass")),
            fqName = FqName("com.example.MyClass")
        )
        assertEquals("com.example.MyClass", key.render())
    }

    @Test
    fun `TypeKey render with only classId`() {
        val key = TypeKey(
            classId = ClassId.topLevel(FqName("com.example.MyClass")),
            fqName = null
        )
        assertEquals("com.example.MyClass", key.render())
    }

    @Test
    fun `TypeKey render with nothing`() {
        val key = TypeKey(classId = null, fqName = null)
        assertEquals("<unknown>", key.render())
    }

    // ================================================================================
    // Negative tests: missing dependency detection
    // ================================================================================

    @Test
    fun `missing dependency is detected`() {
        val registry = BindingRegistry()
        // Service requires Repository, but Repository is not provided
        val req = makeRequirement(typeFqName = "com.example.Repository", paramName = "repo")
        val requirements = listOf(Triple("com.example.Service", null as String?, req))
        val provided = setOf(
            Triple(makeTypeKey("com.example.Service"), null as QualifierValue?, null as String?)
        )

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(1, missing.size)
        assertEquals("com.example.Service", missing[0].first)
        assertEquals("repo", missing[0].second.paramName)
    }

    @Test
    fun `complete graph has no missing dependencies`() {
        val registry = BindingRegistry()
        // Service requires Repository, and Repository IS provided
        val req = makeRequirement(typeFqName = "com.example.Repository", paramName = "repo")
        val requirements = listOf(Triple("com.example.Service", null as String?, req))
        val provided = setOf(
            Triple(makeTypeKey("com.example.Service"), null as QualifierValue?, null as String?),
            Triple(makeTypeKey("com.example.Repository"), null as QualifierValue?, null as String?)
        )

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(0, missing.size)
    }

    @Test
    fun `qualifier mismatch is detected as missing`() {
        val registry = BindingRegistry()
        // Requires @Named("prod") Repository, but only @Named("test") Repository exists
        val req = makeRequirement(
            typeFqName = "com.example.Repository",
            paramName = "repo",
            qualifier = QualifierValue.StringQualifier("prod")
        )
        val requirements = listOf(Triple("com.example.Service", null as String?, req))
        val provided = setOf(
            Triple(
                makeTypeKey("com.example.Repository"),
                QualifierValue.StringQualifier("test") as QualifierValue?,
                null as String?
            )
        )

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(1, missing.size)
    }

    @Test
    fun `matching qualifier is found`() {
        val registry = BindingRegistry()
        val req = makeRequirement(
            typeFqName = "com.example.Repository",
            paramName = "repo",
            qualifier = QualifierValue.StringQualifier("prod")
        )
        val requirements = listOf(Triple("com.example.Service", null as String?, req))
        val provided = setOf(
            Triple(
                makeTypeKey("com.example.Repository"),
                QualifierValue.StringQualifier("prod") as QualifierValue?,
                null as String?
            )
        )

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(0, missing.size)
    }

    @Test
    fun `unqualified requirement does not match qualified provider`() {
        val registry = BindingRegistry()
        // Requires Repository (no qualifier), but only @Named("test") Repository exists
        val req = makeRequirement(typeFqName = "com.example.Repository", paramName = "repo")
        val requirements = listOf(Triple("com.example.Service", null as String?, req))
        val provided = setOf(
            Triple(
                makeTypeKey("com.example.Repository"),
                QualifierValue.StringQualifier("test") as QualifierValue?,
                null as String?
            )
        )

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(1, missing.size)
    }

    @Test
    fun `lazy missing inner type is detected`() {
        val registry = BindingRegistry()
        // Requires Lazy<Repository> (isLazy=true, typeKey=Repository), but Repository not provided
        val req = makeRequirement(
            typeFqName = "com.example.Repository",
            paramName = "repo",
            isLazy = true
        )
        val requirements = listOf(Triple("com.example.Service", null as String?, req))
        val provided = setOf(
            Triple(makeTypeKey("com.example.Service"), null as QualifierValue?, null as String?)
        )

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(1, missing.size)
    }

    @Test
    fun `root scope provider is visible to scoped consumer`() {
        val registry = BindingRegistry()
        // Scoped Service requires root-scope Repository
        val req = makeRequirement(typeFqName = "com.example.Repository", paramName = "repo")
        val requirements = listOf(Triple("com.example.Service", "com.example.SessionScope", req))
        val provided = setOf(
            // Repository is in root scope (scopeFqName = null)
            Triple(makeTypeKey("com.example.Repository"), null as QualifierValue?, null as String?)
        )

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(0, missing.size)
    }

    @Test
    fun `different scope provider is NOT visible to scoped consumer`() {
        val registry = BindingRegistry()
        // Service in SessionScope requires AuthData, but AuthData is in UserScope
        val req = makeRequirement(typeFqName = "com.example.AuthData", paramName = "auth")
        val requirements = listOf(Triple("com.example.Service", "com.example.SessionScope", req))
        val provided = setOf(
            // AuthData is in UserScope (different scope)
            Triple(makeTypeKey("com.example.AuthData"), null as QualifierValue?, "com.example.UserScope" as String?)
        )

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(1, missing.size)
    }

    @Test
    fun `same scope provider IS visible to scoped consumer`() {
        val registry = BindingRegistry()
        val req = makeRequirement(typeFqName = "com.example.AuthData", paramName = "auth")
        val requirements = listOf(Triple("com.example.Service", "com.example.SessionScope", req))
        val provided = setOf(
            Triple(makeTypeKey("com.example.AuthData"), null as QualifierValue?, "com.example.SessionScope" as String?)
        )

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(0, missing.size)
    }

    @Test
    fun `nullable requirement is skipped even when missing`() {
        val registry = BindingRegistry()
        val req = makeRequirement(typeFqName = "com.example.Repository", paramName = "repo", isNullable = true)
        val requirements = listOf(Triple("com.example.Service", null as String?, req))
        val provided = emptySet<Triple<TypeKey, QualifierValue?, String?>>()

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(0, missing.size)
    }

    @Test
    fun `multiple missing dependencies are all reported`() {
        val registry = BindingRegistry()
        val req1 = makeRequirement(typeFqName = "com.example.Repository", paramName = "repo")
        val req2 = makeRequirement(typeFqName = "com.example.Logger", paramName = "logger")
        val requirements = listOf(
            Triple("com.example.Service", null as String?, req1),
            Triple("com.example.Service", null as String?, req2)
        )
        val provided = emptySet<Triple<TypeKey, QualifierValue?, String?>>()

        val missing = registry.validateRequirementsData(requirements, provided)
        assertEquals(2, missing.size)
    }

    // ================================================================================
    // Qualifier matching tests
    // ================================================================================

    @Test
    fun `qualifiers match - both null`() {
        val registry = BindingRegistry()
        assertTrue(registry.qualifiersMatchPublic(null, null))
    }

    @Test
    fun `qualifiers match - same string`() {
        val registry = BindingRegistry()
        assertTrue(registry.qualifiersMatchPublic(
            QualifierValue.StringQualifier("prod"),
            QualifierValue.StringQualifier("prod")
        ))
    }

    @Test
    fun `qualifiers do not match - different string`() {
        val registry = BindingRegistry()
        assertFalse(registry.qualifiersMatchPublic(
            QualifierValue.StringQualifier("prod"),
            QualifierValue.StringQualifier("test")
        ))
    }

    @Test
    fun `qualifiers do not match - one null one not`() {
        val registry = BindingRegistry()
        assertFalse(registry.qualifiersMatchPublic(
            QualifierValue.StringQualifier("prod"),
            null
        ))
        assertFalse(registry.qualifiersMatchPublic(
            null,
            QualifierValue.StringQualifier("prod")
        ))
    }

    // ================================================================================
    // Helpers
    // ================================================================================

    private fun makeTypeKey(fqName: String): TypeKey {
        return TypeKey(
            classId = ClassId.topLevel(FqName(fqName)),
            fqName = FqName(fqName)
        )
    }

    private fun makeRequirement(
        typeFqName: String = "com.example.Dependency",
        paramName: String = "dep",
        isNullable: Boolean = false,
        hasDefault: Boolean = false,
        isInjectedParam: Boolean = false,
        isLazy: Boolean = false,
        isList: Boolean = false,
        isProperty: Boolean = false,
        propertyKey: String? = null,
        qualifier: QualifierValue? = null
    ): Requirement {
        return Requirement(
            typeKey = TypeKey(
                classId = ClassId.topLevel(FqName(typeFqName)),
                fqName = FqName(typeFqName)
            ),
            paramName = paramName,
            isNullable = isNullable,
            hasDefault = hasDefault,
            isInjectedParam = isInjectedParam,
            isLazy = isLazy,
            isList = isList,
            isProperty = isProperty,
            propertyKey = propertyKey,
            qualifier = qualifier
        )
    }
}
