package org.koin.compiler.plugin

import org.jetbrains.kotlin.name.FqName

/**
 * Centralized registry of fully qualified names for Koin annotations.
 *
 * This eliminates magic strings scattered throughout the codebase and provides
 * a single source of truth for annotation FqNames used in both FIR and IR phases.
 */
object KoinAnnotationFqNames {

    // ================================================================================
    // Module Annotations
    // ================================================================================

    /** @Module annotation - marks a class as a Koin module container. */
    val MODULE = FqName("org.koin.core.annotation.Module")

    /** @ComponentScan annotation - enables package scanning for annotated classes. */
    val COMPONENT_SCAN = FqName("org.koin.core.annotation.ComponentScan")

    /** @Configuration annotation - marks a module for auto-discovery. */
    val CONFIGURATION = FqName("org.koin.core.annotation.Configuration")

    // ================================================================================
    // Definition Annotations (Koin)
    // ================================================================================

    /** @Singleton annotation - generates a singleton definition. */
    val SINGLETON = FqName("org.koin.core.annotation.Singleton")

    /** @Single annotation - alias for @Singleton. */
    val SINGLE = FqName("org.koin.core.annotation.Single")

    /** @Factory annotation - generates a factory definition. */
    val FACTORY = FqName("org.koin.core.annotation.Factory")

    /** @Scoped annotation - generates a scoped definition. */
    val SCOPED = FqName("org.koin.core.annotation.Scoped")

    /** @KoinViewModel annotation - generates a viewModel definition. */
    val KOIN_VIEW_MODEL = FqName("org.koin.core.annotation.KoinViewModel")

    /** @KoinWorker annotation - generates a worker definition (Android). */
    val KOIN_WORKER = FqName("org.koin.android.annotation.KoinWorker")

    /** All Koin definition annotations. */
    val KOIN_DEFINITION_ANNOTATIONS = listOf(SINGLETON, SINGLE, FACTORY, SCOPED, KOIN_VIEW_MODEL, KOIN_WORKER)

    // ================================================================================
    // Scope Annotations
    // ================================================================================

    /** @Scope annotation - specifies a scope class for a definition. */
    val SCOPE = FqName("org.koin.core.annotation.Scope")

    // ================================================================================
    // Scope Archetype Annotations
    // ================================================================================

    /** @ViewModelScope annotation - scoped to ViewModel lifecycle. */
    val VIEW_MODEL_SCOPE = FqName("org.koin.core.annotation.ViewModelScope")

    /** @ActivityScope annotation - scoped to Activity lifecycle (Android). */
    val ACTIVITY_SCOPE = FqName("org.koin.android.annotation.ActivityScope")

    /** @ActivityRetainedScope annotation - scoped to retained Activity (Android). */
    val ACTIVITY_RETAINED_SCOPE = FqName("org.koin.android.annotation.ActivityRetainedScope")

    /** @FragmentScope annotation - scoped to Fragment lifecycle (Android). */
    val FRAGMENT_SCOPE = FqName("org.koin.android.annotation.FragmentScope")

    /** All scope archetype annotations. */
    val SCOPE_ARCHETYPES = listOf(VIEW_MODEL_SCOPE, ACTIVITY_SCOPE, ACTIVITY_RETAINED_SCOPE, FRAGMENT_SCOPE)

    // ================================================================================
    // Parameter Annotations (Koin)
    // ================================================================================

    /** @Named annotation - string qualifier for dependencies. */
    val NAMED = FqName("org.koin.core.annotation.Named")

    /** @Qualifier annotation - type or string qualifier for dependencies. */
    val QUALIFIER = FqName("org.koin.core.annotation.Qualifier")

    /** @InjectedParam annotation - marks a parameter for ParametersHolder injection. */
    val INJECTED_PARAM = FqName("org.koin.core.annotation.InjectedParam")

    /** @Property annotation - marks a parameter for property injection. */
    val PROPERTY = FqName("org.koin.core.annotation.Property")

    /** @PropertyValue annotation - provides default value for property injection. */
    val PROPERTY_VALUE = FqName("org.koin.core.annotation.PropertyValue")

    /** @Provided annotation - marks a type as externally provided (skips safety validation). */
    val PROVIDED = FqName("org.koin.core.annotation.Provided")

    /** @ScopeId annotation - injects a value from a named Koin scope. */
    val SCOPE_ID = FqName("org.koin.core.annotation.ScopeId")

    // ================================================================================
    // Application Annotations
    // ================================================================================

    /** @KoinApplication annotation - specifies modules for startKoin<T>(). */
    val KOIN_APPLICATION = FqName("org.koin.core.annotation.KoinApplication")

    // ================================================================================
    // Monitoring Annotations
    // ================================================================================

    /** @Monitor annotation - wraps function body with Kotzilla trace calls. */
    val MONITOR = FqName("org.koin.core.annotation.Monitor")

    // ================================================================================
    // Kotzilla SDK
    // ================================================================================

    /** io.kotzilla.sdk.KotzillaCore class. */
    val KOTZILLA_CORE = FqName("io.kotzilla.sdk.KotzillaCore")

    // ================================================================================
    // JSR-330 Annotations (Jakarta - new package)
    // ================================================================================

    /** jakarta.inject.Singleton - JSR-330 singleton annotation. */
    val JAKARTA_SINGLETON = FqName("jakarta.inject.Singleton")

    /** jakarta.inject.Named - JSR-330 named qualifier annotation. */
    val JAKARTA_NAMED = FqName("jakarta.inject.Named")

    /** jakarta.inject.Inject - JSR-330 inject annotation. */
    val JAKARTA_INJECT = FqName("jakarta.inject.Inject")

    /** jakarta.inject.Qualifier - JSR-330 qualifier meta-annotation. */
    val JAKARTA_QUALIFIER = FqName("jakarta.inject.Qualifier")

    // ================================================================================
    // JSR-330 Annotations (Javax - legacy package)
    // ================================================================================

    /** javax.inject.Singleton - JSR-330 singleton annotation (legacy). */
    val JAVAX_SINGLETON = FqName("javax.inject.Singleton")

    /** javax.inject.Named - JSR-330 named qualifier annotation (legacy). */
    val JAVAX_NAMED = FqName("javax.inject.Named")

    /** javax.inject.Inject - JSR-330 inject annotation (legacy). */
    val JAVAX_INJECT = FqName("javax.inject.Inject")

    /** javax.inject.Qualifier - JSR-330 qualifier meta-annotation (legacy). */
    val JAVAX_QUALIFIER = FqName("javax.inject.Qualifier")

    // ================================================================================
    // Koin Core Classes
    // ================================================================================

    /** org.koin.core.module.Module - Koin module type. */
    val KOIN_MODULE = FqName("org.koin.core.module.Module")

    /** org.koin.core.scope.Scope - Koin scope type. */
    val SCOPE_CLASS = FqName("org.koin.core.scope.Scope")

    /** org.koin.core.parameter.ParametersHolder - Koin parameters holder type. */
    val PARAMETERS_HOLDER = FqName("org.koin.core.parameter.ParametersHolder")

    /** org.koin.dsl - Koin DSL package. */
    val MODULE_DSL = FqName("org.koin.dsl")

    /** org.koin.dsl.ScopeDSL - Koin scope DSL type. */
    val SCOPE_DSL = FqName("org.koin.dsl.ScopeDSL")

    // ================================================================================
    // Koin Plugin Module DSL
    // ================================================================================

    /** org.koin.plugin.module.dsl - Plugin DSL package for generated functions. */
    val PLUGIN_MODULE_DSL = FqName("org.koin.plugin.module.dsl")

    /** org.koin.core.qualifier - Qualifier package. */
    val QUALIFIER_PACKAGE = FqName("org.koin.core.qualifier")

    // ================================================================================
    // Call-Site Resolution Functions (for A4 call-site validation)
    // ================================================================================

    // --- compose ---
    val KOIN_INJECT_COMPOSE = FqName("org.koin.compose.koinInject")

    // --- compose-viewmodel ---
    val KOIN_VIEW_MODEL_COMPOSE = FqName("org.koin.compose.viewmodel.koinViewModel")
    val KOIN_ACTIVITY_VIEW_MODEL_COMPOSE = FqName("org.koin.compose.viewmodel.koinActivityViewModel")
    val KOIN_NAV_VIEW_MODEL_COMPOSE = FqName("org.koin.compose.viewmodel.koinNavViewModel")
    val KOIN_SHARED_VIEW_MODEL_COMPOSE = FqName("org.koin.compose.viewmodel.sharedKoinViewModel")

    // --- androidx-compose ---
    val KOIN_VIEW_MODEL_ANDROIDX_COMPOSE = FqName("org.koin.androidx.compose.koinViewModel")

    // --- androidx-compose-navigation ---
    val KOIN_NAV_VIEW_MODEL_ANDROIDX = FqName("org.koin.androidx.compose.navigation.koinNavViewModel")

    // --- core ---
    val KOIN_COMPONENT_GET = FqName("org.koin.core.component.get")
    val KOIN_COMPONENT_INJECT = FqName("org.koin.core.component.inject")
    // Note: Scope.get/inject excluded — used internally by plugin-generated code (indistinguishable from user code)
    val KOIN_GET = FqName("org.koin.core.Koin.get")
    val KOIN_INJECT = FqName("org.koin.core.Koin.inject")

    // --- android ---
    val ANDROID_COMPONENT_GET = FqName("org.koin.android.ext.android.get")
    val ANDROID_COMPONENT_INJECT = FqName("org.koin.android.ext.android.inject")

    // --- android-viewmodel ---
    val ANDROID_VIEW_MODEL = FqName("org.koin.androidx.viewmodel.ext.android.viewModel")
    val ANDROID_GET_VIEW_MODEL = FqName("org.koin.androidx.viewmodel.ext.android.getViewModel")
    val ANDROID_ACTIVITY_VIEW_MODEL = FqName("org.koin.androidx.viewmodel.ext.android.activityViewModel")
    val ANDROID_GET_ACTIVITY_VIEW_MODEL = FqName("org.koin.androidx.viewmodel.ext.android.getActivityViewModel")

    // --- ktor ---
    val KTOR_GET = FqName("org.koin.ktor.ext.get")
    val KTOR_INJECT = FqName("org.koin.ktor.ext.inject")

    /** All call-site resolution functions for A4 validation. */
    val CALL_SITE_RESOLUTION_FUNCTIONS = listOf(
        // compose
        KOIN_INJECT_COMPOSE,
        // compose-viewmodel
        KOIN_VIEW_MODEL_COMPOSE, KOIN_ACTIVITY_VIEW_MODEL_COMPOSE,
        KOIN_NAV_VIEW_MODEL_COMPOSE, KOIN_SHARED_VIEW_MODEL_COMPOSE,
        // androidx-compose
        KOIN_VIEW_MODEL_ANDROIDX_COMPOSE,
        // androidx-compose-navigation
        KOIN_NAV_VIEW_MODEL_ANDROIDX,
        // core
        KOIN_COMPONENT_GET, KOIN_COMPONENT_INJECT,
        KOIN_GET, KOIN_INJECT,
        // android
        ANDROID_COMPONENT_GET, ANDROID_COMPONENT_INJECT,
        // android-viewmodel
        ANDROID_VIEW_MODEL, ANDROID_GET_VIEW_MODEL,
        ANDROID_ACTIVITY_VIEW_MODEL, ANDROID_GET_ACTIVITY_VIEW_MODEL,
        // ktor
        KTOR_GET, KTOR_INJECT
    )

    // ================================================================================
    // Kotlin Standard Library
    // ================================================================================

    /** kotlin.reflect.KClass - KClass type for class references. */
    val KCLASS = FqName("kotlin.reflect.KClass")

    /** kotlin.Function1 - Single parameter function type. */
    val FUNCTION1 = FqName("kotlin.Function1")

    /** kotlin.Function2 - Two parameter function type. */
    val FUNCTION2 = FqName("kotlin.Function2")

    /** kotlin.LazyThreadSafetyMode - Lazy initialization mode. */
    val LAZY_THREAD_SAFETY_MODE = FqName("kotlin.LazyThreadSafetyMode")

    /** kotlin.Unit - Unit type. */
    val UNIT = FqName("kotlin.Unit")

    /** kotlin.Any - Any type. */
    val ANY = FqName("kotlin.Any")
}
