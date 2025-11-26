package org.koin.compiler.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for KoinAnnotationFqNames - verifies FqName values are correct.
 */
class KoinAnnotationFqNamesTest {

    @Test
    fun `module annotations have correct FqNames`() {
        assertEquals("org.koin.core.annotation.Module", KoinAnnotationFqNames.MODULE.asString())
        assertEquals("org.koin.core.annotation.ComponentScan", KoinAnnotationFqNames.COMPONENT_SCAN.asString())
        assertEquals("org.koin.core.annotation.Configuration", KoinAnnotationFqNames.CONFIGURATION.asString())
    }

    @Test
    fun `definition annotations have correct FqNames`() {
        assertEquals("org.koin.core.annotation.Singleton", KoinAnnotationFqNames.SINGLETON.asString())
        assertEquals("org.koin.core.annotation.Single", KoinAnnotationFqNames.SINGLE.asString())
        assertEquals("org.koin.core.annotation.Factory", KoinAnnotationFqNames.FACTORY.asString())
        assertEquals("org.koin.core.annotation.Scoped", KoinAnnotationFqNames.SCOPED.asString())
        assertEquals("org.koin.core.annotation.KoinViewModel", KoinAnnotationFqNames.KOIN_VIEW_MODEL.asString())
        assertEquals("org.koin.android.annotation.KoinWorker", KoinAnnotationFqNames.KOIN_WORKER.asString())
    }

    @Test
    fun `scope annotations have correct FqNames`() {
        assertEquals("org.koin.core.annotation.Scope", KoinAnnotationFqNames.SCOPE.asString())
        assertEquals("org.koin.core.annotation.ViewModelScope", KoinAnnotationFqNames.VIEW_MODEL_SCOPE.asString())
        assertEquals("org.koin.android.annotation.ActivityScope", KoinAnnotationFqNames.ACTIVITY_SCOPE.asString())
        assertEquals("org.koin.android.annotation.ActivityRetainedScope", KoinAnnotationFqNames.ACTIVITY_RETAINED_SCOPE.asString())
        assertEquals("org.koin.android.annotation.FragmentScope", KoinAnnotationFqNames.FRAGMENT_SCOPE.asString())
    }

    @Test
    fun `parameter annotations have correct FqNames`() {
        assertEquals("org.koin.core.annotation.Named", KoinAnnotationFqNames.NAMED.asString())
        assertEquals("org.koin.core.annotation.Qualifier", KoinAnnotationFqNames.QUALIFIER.asString())
        assertEquals("org.koin.core.annotation.InjectedParam", KoinAnnotationFqNames.INJECTED_PARAM.asString())
        assertEquals("org.koin.core.annotation.Property", KoinAnnotationFqNames.PROPERTY.asString())
        assertEquals("org.koin.core.annotation.PropertyValue", KoinAnnotationFqNames.PROPERTY_VALUE.asString())
    }

    @Test
    fun `jakarta annotations have correct FqNames`() {
        assertEquals("jakarta.inject.Singleton", KoinAnnotationFqNames.JAKARTA_SINGLETON.asString())
        assertEquals("jakarta.inject.Named", KoinAnnotationFqNames.JAKARTA_NAMED.asString())
        assertEquals("jakarta.inject.Inject", KoinAnnotationFqNames.JAKARTA_INJECT.asString())
        assertEquals("jakarta.inject.Qualifier", KoinAnnotationFqNames.JAKARTA_QUALIFIER.asString())
    }

    @Test
    fun `javax annotations have correct FqNames`() {
        assertEquals("javax.inject.Singleton", KoinAnnotationFqNames.JAVAX_SINGLETON.asString())
        assertEquals("javax.inject.Named", KoinAnnotationFqNames.JAVAX_NAMED.asString())
        assertEquals("javax.inject.Inject", KoinAnnotationFqNames.JAVAX_INJECT.asString())
        assertEquals("javax.inject.Qualifier", KoinAnnotationFqNames.JAVAX_QUALIFIER.asString())
    }

    @Test
    fun `koin core classes have correct FqNames`() {
        assertEquals("org.koin.core.module.Module", KoinAnnotationFqNames.KOIN_MODULE.asString())
        assertEquals("org.koin.core.scope.Scope", KoinAnnotationFqNames.SCOPE_CLASS.asString())
        assertEquals("org.koin.core.parameter.ParametersHolder", KoinAnnotationFqNames.PARAMETERS_HOLDER.asString())
        assertEquals("org.koin.dsl", KoinAnnotationFqNames.MODULE_DSL.asString())
        assertEquals("org.koin.dsl.ScopeDSL", KoinAnnotationFqNames.SCOPE_DSL.asString())
    }

    @Test
    fun `plugin DSL package has correct FqName`() {
        assertEquals("org.koin.plugin.module.dsl", KoinAnnotationFqNames.PLUGIN_MODULE_DSL.asString())
        assertEquals("org.koin.core.qualifier", KoinAnnotationFqNames.QUALIFIER_PACKAGE.asString())
    }

    @Test
    fun `kotlin types have correct FqNames`() {
        assertEquals("kotlin.reflect.KClass", KoinAnnotationFqNames.KCLASS.asString())
        assertEquals("kotlin.Function1", KoinAnnotationFqNames.FUNCTION1.asString())
        assertEquals("kotlin.Function2", KoinAnnotationFqNames.FUNCTION2.asString())
        assertEquals("kotlin.LazyThreadSafetyMode", KoinAnnotationFqNames.LAZY_THREAD_SAFETY_MODE.asString())
        assertEquals("kotlin.Unit", KoinAnnotationFqNames.UNIT.asString())
        assertEquals("kotlin.Any", KoinAnnotationFqNames.ANY.asString())
    }

    @Test
    fun `koin definition annotations list contains all definition annotations`() {
        val annotations = KoinAnnotationFqNames.KOIN_DEFINITION_ANNOTATIONS
        assertEquals(6, annotations.size)
        assertTrue(annotations.contains(KoinAnnotationFqNames.SINGLETON))
        assertTrue(annotations.contains(KoinAnnotationFqNames.SINGLE))
        assertTrue(annotations.contains(KoinAnnotationFqNames.FACTORY))
        assertTrue(annotations.contains(KoinAnnotationFqNames.SCOPED))
        assertTrue(annotations.contains(KoinAnnotationFqNames.KOIN_VIEW_MODEL))
        assertTrue(annotations.contains(KoinAnnotationFqNames.KOIN_WORKER))
    }

    @Test
    fun `scope archetypes list contains all scope archetype annotations`() {
        val archetypes = KoinAnnotationFqNames.SCOPE_ARCHETYPES
        assertEquals(4, archetypes.size)
        assertTrue(archetypes.contains(KoinAnnotationFqNames.VIEW_MODEL_SCOPE))
        assertTrue(archetypes.contains(KoinAnnotationFqNames.ACTIVITY_SCOPE))
        assertTrue(archetypes.contains(KoinAnnotationFqNames.ACTIVITY_RETAINED_SCOPE))
        assertTrue(archetypes.contains(KoinAnnotationFqNames.FRAGMENT_SCOPE))
    }
}
