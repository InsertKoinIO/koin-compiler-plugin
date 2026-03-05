package org.koin.compiler.plugin.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.koin.compiler.plugin.KoinPluginLogger
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.createTopLevelFunction
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProvider
import org.jetbrains.kotlin.fir.deserialization.toQualifiedPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.toFirResolvedTypeRef
import org.jetbrains.kotlin.types.ConstantValueKind

import org.jetbrains.kotlin.KtPsiSourceElement
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.isJs
import org.jetbrains.kotlin.platform.isWasm
import org.jetbrains.kotlin.platform.konan.isNative
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginConstants

// Note: Prior to Kotlin 2.3.20, KLIB-based targets (Native, JS, Wasm) could not handle
// FIR-generated top-level declarations (KT-58886, KT-75865). Starting from 2.3.20-Beta1,
// all platforms support FIR-generated declarations with synthetic file names.

/**
 * FIR extension that generates:
 * 1. Module extension functions for classes annotated with @Module
 * 2. Hint functions in `org.koin.plugin.hints` package for @Configuration modules (cross-module discovery)
 *
 * Examples:
 * ```kotlin
 * @Module
 * class MyModule {
 *     @Single fun provideService(): Service = ServiceImpl()
 * }
 * ```
 * Generates: `fun MyModule.module(): Module = ...`
 *
 * ```kotlin
 * @Module
 * @Configuration  // Auto-discovered by startKoin<T>()
 * class MyConfigModule
 * ```
 * Generates:
 * - `fun MyConfigModule.module(): Module = ...`
 * - `fun org.koin.plugin.hints.configuration(contributed: MyConfigModule): Unit` (hint for cross-module discovery)
 *
 * Note: @ComponentScan is only needed for scanning packages for annotated classes, not for @Configuration.
 * Note: Using a function instead of a property avoids NPE on Kotlin/Native due to backing field issues.
 *
 * The hint functions allow downstream modules to discover @Configuration modules from dependencies
 * by querying the `org.koin.plugin.hints` package via FIR's symbolProvider.
 */
@OptIn(SymbolInternals::class, ExperimentalTopLevelDeclarationsGenerationApi::class, org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess::class)
class KoinModuleFirGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {

    companion object {
        // Annotations from koin-annotations library - use centralized registry
        private val MODULE_ANNOTATION = KoinAnnotationFqNames.MODULE
        private val CONFIGURATION_ANNOTATION = KoinAnnotationFqNames.CONFIGURATION

        // Definition annotations
        private val SINGLETON_ANNOTATION = KoinAnnotationFqNames.SINGLETON
        private val SINGLE_ANNOTATION = KoinAnnotationFqNames.SINGLE
        private val FACTORY_ANNOTATION = KoinAnnotationFqNames.FACTORY
        private val SCOPED_ANNOTATION = KoinAnnotationFqNames.SCOPED
        private val KOIN_VIEW_MODEL_ANNOTATION = KoinAnnotationFqNames.KOIN_VIEW_MODEL
        private val KOIN_WORKER_ANNOTATION = KoinAnnotationFqNames.KOIN_WORKER

        // Scope archetype annotations (also define SCOPED definitions)
        private val VIEW_MODEL_SCOPE_ANNOTATION = KoinAnnotationFqNames.VIEW_MODEL_SCOPE
        private val ACTIVITY_SCOPE_ANNOTATION = KoinAnnotationFqNames.ACTIVITY_SCOPE
        private val ACTIVITY_RETAINED_SCOPE_ANNOTATION = KoinAnnotationFqNames.ACTIVITY_RETAINED_SCOPE
        private val FRAGMENT_SCOPE_ANNOTATION = KoinAnnotationFqNames.FRAGMENT_SCOPE

        // JSR-330 annotations (jakarta.inject and javax.inject)
        private val JAKARTA_SINGLETON_ANNOTATION = KoinAnnotationFqNames.JAKARTA_SINGLETON
        private val JAKARTA_INJECT_ANNOTATION = KoinAnnotationFqNames.JAKARTA_INJECT
        private val JAVAX_SINGLETON_ANNOTATION = KoinAnnotationFqNames.JAVAX_SINGLETON
        private val JAVAX_INJECT_ANNOTATION = KoinAnnotationFqNames.JAVAX_INJECT

        // Koin classes
        private val KOIN_MODULE_CLASS_ID = ClassId.topLevel(KoinAnnotationFqNames.KOIN_MODULE)
        private val COMPONENT_SCAN_ANNOTATION = KoinAnnotationFqNames.COMPONENT_SCAN

        // Generated names
        private val MODULE_FUNCTION_NAME = Name.identifier(KoinPluginConstants.MODULE_FUNCTION_NAME)

        // Hint package for all @Configuration modules and definition classes
        // Function names are label-specific: configuration_<label> (e.g., configuration_default, configuration_test)
        // Definition function names: definition_<type> (e.g., definition_single, definition_viewmodel)
        val HINTS_PACKAGE = FqName(KoinPluginConstants.HINTS_PACKAGE)
        private const val HINT_FUNCTION_PREFIX = KoinPluginConstants.HINT_FUNCTION_PREFIX
        private const val DEFINITION_HINT_PREFIX = KoinPluginConstants.DEFINITION_HINT_PREFIX
        private const val DEFINITION_FUNCTION_HINT_PREFIX = KoinPluginConstants.DEFINITION_FUNCTION_HINT_PREFIX

        /**
         * Get the hint function name for a specific label.
         * Example: "test" -> "configuration_test", "default" -> "configuration_default"
         */
        fun hintFunctionNameForLabel(label: String): Name = Name.identifier("$HINT_FUNCTION_PREFIX$label")

        /**
         * Extract label from a hint function name.
         * Example: "configuration_test" -> "test", "configuration_default" -> "default"
         * Returns null if the function name doesn't match the hint pattern.
         */
        fun labelFromHintFunctionName(functionName: String): String? {
            return if (functionName.startsWith(HINT_FUNCTION_PREFIX)) {
                functionName.removePrefix(HINT_FUNCTION_PREFIX)
            } else {
                null
            }
        }

        /**
         * Get the hint function name for a definition type.
         * Example: "single" -> "definition_single", "viewmodel" -> "definition_viewmodel"
         */
        fun definitionHintFunctionName(type: String): Name = Name.identifier("$DEFINITION_HINT_PREFIX$type")

        /**
         * Extract definition type from a hint function name.
         * Example: "definition_single" -> "single", "definition_viewmodel" -> "viewmodel"
         * Returns null if the function name doesn't match the definition hint pattern.
         */
        fun definitionTypeFromHintFunctionName(functionName: String): String? {
            // Don't match definition_function_* prefix (more specific prefix check first)
            if (functionName.startsWith(DEFINITION_FUNCTION_HINT_PREFIX)) return null
            return if (functionName.startsWith(DEFINITION_HINT_PREFIX)) {
                functionName.removePrefix(DEFINITION_HINT_PREFIX)
            } else {
                null
            }
        }

        /**
         * Get the hint function name for a top-level function definition type.
         * Example: "single" -> "definition_function_single"
         */
        fun definitionFunctionHintFunctionName(type: String): Name = Name.identifier("$DEFINITION_FUNCTION_HINT_PREFIX$type")

        /**
         * Extract definition type from a function hint name.
         * Example: "definition_function_single" -> "single"
         * Returns null if the function name doesn't match the function hint pattern.
         */
        fun definitionTypeFromFunctionHintName(functionName: String): String? {
            return if (functionName.startsWith(DEFINITION_FUNCTION_HINT_PREFIX)) {
                functionName.removePrefix(DEFINITION_FUNCTION_HINT_PREFIX)
            } else {
                null
            }
        }

        // Module-scoped component scan hint prefixes
        private const val COMPONENT_SCAN_HINT_PREFIX = KoinPluginConstants.COMPONENT_SCAN_HINT_PREFIX
        private const val COMPONENT_SCAN_FUNCTION_HINT_PREFIX = KoinPluginConstants.COMPONENT_SCAN_FUNCTION_HINT_PREFIX

        /**
         * Sanitize a module ClassId into a collision-free identifier for hint function names.
         * Uses the FQName with dots replaced by underscores, preserving segment boundaries.
         * Root-package classes use just the short class name (lowercased first char).
         *
         * Example: ClassId("com.example", "CoreModule") -> "com_example_CoreModule"
         * Example: ClassId("", "CoreModule") -> "coreModule"
         */
        fun sanitizeModuleIdForHint(classId: ClassId): String {
            val fqName = classId.asSingleFqName().asString()
            return if (classId.packageFqName.isRoot) {
                fqName.replaceFirstChar { it.lowercaseChar() }
            } else {
                fqName.replace('.', '_')
            }
        }

        /**
         * Build hint function name for a module-scoped component scan class definition.
         * Example: ("com_example_CoreModule", "single") -> "componentscan_com_example_CoreModule_single"
         */
        fun moduleScanHintFunctionName(moduleId: String, defType: String): Name =
            Name.identifier("$COMPONENT_SCAN_HINT_PREFIX${moduleId}_$defType")

        /**
         * Parse a module-scoped component scan hint function name back to (moduleId, defType).
         * The defType is always one of [ALL_DEFINITION_TYPES], so we split on the last `_` that
         * yields a known defType. This avoids ambiguity when moduleId itself contains underscores.
         *
         * Example: "componentscan_com_example_CoreModule_single" -> ("com_example_CoreModule", "single")
         * Returns null if the function name doesn't match the pattern.
         */
        fun moduleScanInfoFromHintFunctionName(functionName: String): Pair<String, String>? {
            // Don't match componentscanfunc_* prefix
            if (functionName.startsWith(COMPONENT_SCAN_FUNCTION_HINT_PREFIX)) return null
            if (!functionName.startsWith(COMPONENT_SCAN_HINT_PREFIX)) return null
            val remainder = functionName.removePrefix(COMPONENT_SCAN_HINT_PREFIX)
            val lastUnderscore = remainder.lastIndexOf('_')
            if (lastUnderscore <= 0) return null
            val moduleId = remainder.substring(0, lastUnderscore)
            val defType = remainder.substring(lastUnderscore + 1)
            if (defType !in ALL_DEFINITION_TYPES) return null
            return moduleId to defType
        }

        /**
         * Build hint function name for a module-scoped component scan function definition.
         * Example: ("com_example_CoreModule", "single") -> "componentscanfunc_com_example_CoreModule_single"
         */
        fun moduleScanFunctionHintFunctionName(moduleId: String, defType: String): Name =
            Name.identifier("$COMPONENT_SCAN_FUNCTION_HINT_PREFIX${moduleId}_$defType")

        /**
         * Parse a module-scoped component scan function hint name back to (moduleId, defType).
         * Example: "componentscanfunc_com_example_CoreModule_single" -> ("com_example_CoreModule", "single")
         * Returns null if the function name doesn't match the pattern.
         */
        fun moduleScanFunctionInfoFromHintFunctionName(functionName: String): Pair<String, String>? {
            if (!functionName.startsWith(COMPONENT_SCAN_FUNCTION_HINT_PREFIX)) return null
            val remainder = functionName.removePrefix(COMPONENT_SCAN_FUNCTION_HINT_PREFIX)
            val lastUnderscore = remainder.lastIndexOf('_')
            if (lastUnderscore <= 0) return null
            val moduleId = remainder.substring(0, lastUnderscore)
            val defType = remainder.substring(lastUnderscore + 1)
            if (defType !in ALL_DEFINITION_TYPES) return null
            return moduleId to defType
        }

        // Definition types for hint functions - use shared constants
        const val DEF_TYPE_SINGLE = KoinPluginConstants.DEF_TYPE_SINGLE
        const val DEF_TYPE_FACTORY = KoinPluginConstants.DEF_TYPE_FACTORY
        const val DEF_TYPE_SCOPED = KoinPluginConstants.DEF_TYPE_SCOPED
        const val DEF_TYPE_VIEWMODEL = KoinPluginConstants.DEF_TYPE_VIEWMODEL
        const val DEF_TYPE_WORKER = KoinPluginConstants.DEF_TYPE_WORKER

        val ALL_DEFINITION_TYPES = KoinPluginConstants.ALL_DEFINITION_TYPES

        /**
         * Generate a deterministic synthetic file name for FIR-generated functions.
         * Uses package segments + class name + suffix, all capitalized and joined.
         * Example: "com.example.DataModule" + "Module" -> "comExampleDataModuleModule.kt"
         *
         * This allows K/Native to work with synthetic files since the name is consistent across compilation phases.
         */
        fun syntheticFileName(classId: ClassId, suffix: String): String {
            val parts = sequence {
                yieldAll(classId.packageFqName.pathSegments().map { it.asString() })
                yield(classId.shortClassName.asString())
                yield(suffix)
            }
            val fileName = parts
                .map { segment -> segment.replaceFirstChar { it.uppercaseChar() } }
                .joinToString(separator = "")
                .replaceFirstChar { it.lowercaseChar() }
            return "$fileName.kt"
        }
    }

    /**
     * Holds a @Configuration module with its labels and source file info.
     * The containing file name is captured at discovery time for use in hint generation.
     */
    private data class ConfigurationModule(
        val classSymbol: FirClassSymbol<*>,
        val labels: List<String>,
        val containingFileName: String?
    )

    /**
     * Holds a @Module class with its source file information.
     * We capture the source file name during discovery because it may not be available
     * later when generating functions (due to KMP compilation phases).
     */
    private data class ModuleClassInfo(
        val classSymbol: FirClassSymbol<*>,
        val containingFileName: String?
    )

    /**
     * Holds a definition class (@Singleton, @Factory, @KoinViewModel, etc.) with its type and source info.
     * Used for cross-module discovery via hint functions.
     */
    private data class DefinitionClassInfo(
        val classSymbol: FirClassSymbol<*>,
        val definitionType: String, // single, factory, scoped, viewmodel, worker
        val containingFileName: String?
    )

    /**
     * Holds a top-level function with a definition annotation and its return type.
     * Used for cross-module discovery via function hint functions.
     * The return type ClassId is what this function provides to the DI container.
     */
    private data class DefinitionFunctionInfo(
        val functionSymbol: FirNamedFunctionSymbol,
        val definitionType: String, // single, factory, scoped, viewmodel, worker
        val containingFileName: String?,
        val returnTypeClassId: ClassId
    )

    // Platform info for logging
    private val platformInfo: String by lazy {
        val platform = session.moduleData.platform
        val parts = mutableListOf<String>()
        if (platform.isNative()) parts.add("native")
        if (platform.isJs()) parts.add("js")
        if (platform.isWasm()) parts.add("wasm")
        if (parts.isEmpty()) parts.add("jvm")
        parts.joinToString("/").also { log { "Platform: $it" } }
    }


    /**
     * Mark a FIR-generated hint function as @Deprecated(level = HIDDEN).
     * This prevents the function from being exported to ObjC headers on Kotlin/Native,
     * which would otherwise crash with "An operation is not implemented" in findSourceFile.
     * Same approach used by Metro (https://github.com/ZacSweers/metro).
     */
    private fun FirCallableDeclaration.markAsDeprecatedHidden() {
        val deprecatedSymbol = session.symbolProvider
            .getClassLikeSymbolByClassId(StandardClassIds.Annotations.Deprecated) as? FirRegularClassSymbol
            ?: return

        val annotation = buildAnnotation {
            annotationTypeRef = deprecatedSymbol.defaultType().toFirResolvedTypeRef()
            argumentMapping = buildAnnotationArgumentMapping {
                mapping[Name.identifier("message")] = buildLiteralExpression(
                    null, ConstantValueKind.String,
                    "Koin compiler plugin internal hint function", setType = true
                )
                mapping[Name.identifier("level")] = buildEnumEntryDeserializedAccessExpression {
                    enumClassId = StandardClassIds.DeprecationLevel
                    enumEntryName = Name.identifier("HIDDEN")
                }.toQualifiedPropertyAccessExpression(session)
            }
        }
        replaceAnnotations(annotations + listOf(annotation))
        replaceDeprecationsProvider(getDeprecationsProvider(session))
    }

    // Cache of module classes found (@Module) - generates .module extension property
    // Excludes `expect` classes - only `actual` classes should have module() generated
    // Stores source file info at discovery time for later use in generateFunctions
    private val moduleClassInfos: List<ModuleClassInfo> by lazy {
        val provider = session.predicateBasedProvider

        provider.getSymbolsByPredicate(modulePredicate)
            .filterIsInstance<FirClassSymbol<*>>()
            .filter { classSymbol ->
                // Skip expect classes - they don't have implementation
                val isExpect = classSymbol.rawStatus.isExpect
                if (isExpect) {
                    log { "  Skipping expect class: ${classSymbol.classId}" }
                }
                !isExpect
            }
            .mapNotNull { classSymbol ->
                // Capture source file info NOW while it's available
                val source = classSymbol.fir.source
                val sourceKind = source?.kind
                val sourceType = source?.javaClass?.simpleName

                // Determine if this is a source class or a dependency class
                // KtPsiSourceElement - standard source PSI elements (direct PSI access)
                // KtLightSourceElement with RealSourceElementKind - KMP source files (no direct PSI)
                // Other types (null, metadata) - dependency classes from JARs
                val containingFileName = when (source) {
                    is KtPsiSourceElement -> {
                        val file = source.psi.containingFile
                        log { "    KtPsiSourceElement: psi=${source.psi.javaClass.simpleName}, file=${file?.name}" }
                        file?.name
                    }
                    else -> {
                        // Check if this is a real source element (KtRealSourceElementKind) vs synthetic
                        val isRealSource = sourceKind?.toString()?.contains("RealSourceElementKind") == true
                        if (isRealSource) {
                            // Use deterministic synthetic file names
                            val syntheticName = syntheticFileName(classSymbol.classId, "Module")
                            log { "    RealSourceElement ($sourceType): using synthetic file name=$syntheticName, platform=$platformInfo" }
                            syntheticName
                        } else {
                            // Skip classes from dependencies (JARs/metadata)
                            log { "    Skipping class from dependency: $sourceType (kind=$sourceKind)" }
                            return@mapNotNull null
                        }
                    }
                }
                log { "  Found @Module class: ${classSymbol.classId} (sourceFile=$containingFileName, sourceType=$sourceType)" }
                ModuleClassInfo(classSymbol, containingFileName)
            }
    }

    // Cached list of module class symbols (extracted from moduleClassInfos)
    private val moduleClasses: List<FirClassSymbol<*>> by lazy {
        moduleClassInfos.map { it.classSymbol }
    }

    // Cache of configuration modules (@Module @Configuration) with their labels - for auto-discovery
    // Note: @ComponentScan is NOT required for @Configuration
    // Uses moduleClassInfos to get pre-captured source file info for hint generation
    private val configurationModules: List<ConfigurationModule> by lazy {
        log { "Looking for @Configuration modules among ${moduleClassInfos.size} @Module classes" }

        // Use predicate-based discovery as primary mechanism for detecting @Configuration.
        // This works reliably in multi-target KMP where coneTypeOrNull may return null
        // for annotation type refs on KtLightSourceElement classes (commonMain in platform compilations).
        val configClassIds = session.predicateBasedProvider
            .getSymbolsByPredicate(configurationPredicate)
            .filterIsInstance<FirClassSymbol<*>>()
            .map { it.classId }
            .toSet()
        log { "  Predicate found ${configClassIds.size} @Configuration classes: $configClassIds" }

        val modules = moduleClassInfos.mapNotNull { moduleInfo ->
            val classSymbol = moduleInfo.classSymbol

            // Primary: check via predicate (reliable in KMP multi-target)
            val hasConfigurationViaPredicate = classSymbol.classId in configClassIds

            // Try to find the annotation via coneTypeOrNull for label extraction
            val configAnnotation = classSymbol.fir.annotations
                .filterIsInstance<FirAnnotationCall>()
                .firstOrNull { annotation ->
                    val annotationClassId = annotation.annotationTypeRef.coneTypeOrNull?.classId
                    annotationClassId?.asSingleFqName() == CONFIGURATION_ANNOTATION
                }

            if (hasConfigurationViaPredicate || configAnnotation != null) {
                // Extract labels: use annotation arguments if available, default to ["default"]
                val labels = if (configAnnotation != null) {
                    extractConfigurationLabels(configAnnotation)
                } else {
                    listOf(KoinPluginConstants.DEFAULT_LABEL)
                }
                log { "  -> ${classSymbol.classId}: @Configuration labels=$labels, file=${moduleInfo.containingFileName} (predicate=$hasConfigurationViaPredicate, coneType=${configAnnotation != null})" }
                ConfigurationModule(classSymbol, labels, moduleInfo.containingFileName)
            } else {
                log { "  -> ${classSymbol.classId}: no @Configuration" }
                null
            }
        }
        log { "Found ${modules.size} @Configuration modules" }
        modules
    }

    /**
     * Extract labels from @Configuration annotation.
     * @Configuration("test", "prod") -> ["test", "prod"]
     * @Configuration() or @Configuration -> ["default"]
     */
    private fun extractConfigurationLabels(annotation: FirAnnotationCall): List<String> {
        // @Configuration uses vararg value: String
        val argumentList = annotation.argumentList.arguments
        if (argumentList.isEmpty()) {
            return listOf(KoinPluginConstants.DEFAULT_LABEL)
        }

        // The value parameter contains the labels
        val labels = mutableListOf<String>()
        for (argument in argumentList) {
            when (argument) {
                is FirVarargArgumentsExpression -> {
                    // Vararg/Array of strings: @Configuration("test", "prod") or @Configuration(value = ["test", "prod"])
                    for (element in argument.arguments) {
                        if (element is FirLiteralExpression) {
                            val value = element.value
                            if (value is String) {
                                labels.add(value)
                            }
                        }
                    }
                }
                is FirLiteralExpression -> {
                    // Single string: @Configuration("test")
                    val value = argument.value
                    if (value is String) {
                        labels.add(value)
                    }
                }
                else -> {
                    log { "  -> Unknown expression type for @Configuration: ${argument::class.simpleName}" }
                }
            }
        }

        return labels.ifEmpty { listOf(KoinPluginConstants.DEFAULT_LABEL) }
    }

    // Predicate for @Module annotated classes
    private val modulePredicate = LookupPredicate.create { annotated(MODULE_ANNOTATION) }

    // Predicates for @Configuration and @ComponentScan - used for KMP-safe annotation detection
    // In multi-target KMP, coneTypeOrNull on annotation type refs may return null for KtLightSourceElement classes,
    // so we use predicates as the primary detection mechanism (they use the compiler's internal annotation matching)
    private val configurationPredicate = LookupPredicate.create { annotated(CONFIGURATION_ANNOTATION) }
    private val componentScanPredicate = LookupPredicate.create { annotated(COMPONENT_SCAN_ANNOTATION) }

    // Predicates for definition annotations - used for cross-module @ComponentScan discovery
    private val singletonPredicate = LookupPredicate.create { annotated(SINGLETON_ANNOTATION) }
    private val singlePredicate = LookupPredicate.create { annotated(SINGLE_ANNOTATION) }
    private val factoryPredicate = LookupPredicate.create { annotated(FACTORY_ANNOTATION) }
    private val scopedPredicate = LookupPredicate.create { annotated(SCOPED_ANNOTATION) }
    private val viewModelPredicate = LookupPredicate.create { annotated(KOIN_VIEW_MODEL_ANNOTATION) }
    private val workerPredicate = LookupPredicate.create { annotated(KOIN_WORKER_ANNOTATION) }
    // Scope archetype predicates
    private val viewModelScopePredicate = LookupPredicate.create { annotated(VIEW_MODEL_SCOPE_ANNOTATION) }
    private val activityScopePredicate = LookupPredicate.create { annotated(ACTIVITY_SCOPE_ANNOTATION) }
    private val activityRetainedScopePredicate = LookupPredicate.create { annotated(ACTIVITY_RETAINED_SCOPE_ANNOTATION) }
    private val fragmentScopePredicate = LookupPredicate.create { annotated(FRAGMENT_SCOPE_ANNOTATION) }
    // JSR-330 predicates
    private val jakartaSingletonPredicate = LookupPredicate.create { annotated(JAKARTA_SINGLETON_ANNOTATION) }
    private val jakartaInjectPredicate = LookupPredicate.create { annotated(JAKARTA_INJECT_ANNOTATION) }
    private val javaxSingletonPredicate = LookupPredicate.create { annotated(JAVAX_SINGLETON_ANNOTATION) }
    private val javaxInjectPredicate = LookupPredicate.create { annotated(JAVAX_INJECT_ANNOTATION) }

    // Collect packages scanned by local @Module @ComponentScan classes
    // Used to filter definition hints - only generate hints for "orphan" definitions not covered locally
    private val localScanPackages: Set<String> by lazy {
        // Use predicate-based discovery as primary mechanism for detecting @ComponentScan.
        // This works reliably in multi-target KMP where coneTypeOrNull may return null
        // for annotation type refs on KtLightSourceElement classes (commonMain in platform compilations).
        val scanClassIds = session.predicateBasedProvider
            .getSymbolsByPredicate(componentScanPredicate)
            .filterIsInstance<FirClassSymbol<*>>()
            .map { it.classId }
            .toSet()
        log { "  Predicate found ${scanClassIds.size} @ComponentScan classes: $scanClassIds" }

        val packages = mutableSetOf<String>()
        for (moduleClassInfo in moduleClassInfos) {
            val classSymbol = moduleClassInfo.classSymbol

            // Primary: check via predicate (reliable in KMP multi-target)
            val hasComponentScanViaPredicate = classSymbol.classId in scanClassIds

            // Try to find the annotation via coneTypeOrNull for package extraction
            val componentScanAnnotation = classSymbol.fir.annotations
                .filterIsInstance<FirAnnotationCall>()
                .firstOrNull { annotation ->
                    val annotationClassId = annotation.annotationTypeRef.coneTypeOrNull?.classId
                    annotationClassId?.asSingleFqName() == COMPONENT_SCAN_ANNOTATION
                }

            if (hasComponentScanViaPredicate || componentScanAnnotation != null) {
                // Extract packages: use annotation arguments if available, default to class's own package
                val scanPkgs = if (componentScanAnnotation != null) {
                    extractComponentScanPackages(componentScanAnnotation, classSymbol)
                } else {
                    listOf(classSymbol.classId.packageFqName.asString())
                }
                log { "  -> ${classSymbol.classId}: @ComponentScan packages=$scanPkgs (predicate=$hasComponentScanViaPredicate, coneType=${componentScanAnnotation != null})" }
                packages.addAll(scanPkgs)
            }
        }
        log { "Local scan packages: $packages" }
        packages
    }

    /**
     * Check if a package is covered by any local @ComponentScan.
     * A package is covered if it equals a scan package or is a sub-package.
     */
    private fun isCoveredByLocalScan(packageName: String): Boolean {
        return localScanPackages.any { scanPkg ->
            packageName == scanPkg || packageName.startsWith("$scanPkg.")
        }
    }

    /**
     * Extract packages from @ComponentScan annotation.
     * If empty, uses the class's package.
     */
    private fun extractComponentScanPackages(annotation: FirAnnotationCall, classSymbol: FirClassSymbol<*>): List<String> {
        val packages = mutableListOf<String>()
        val argumentList = annotation.argumentList.arguments
        for (argument in argumentList) {
            when (argument) {
                is FirVarargArgumentsExpression -> {
                    for (element in argument.arguments) {
                        if (element is FirLiteralExpression) {
                            val value = element.value
                            if (value is String) {
                                packages.add(value)
                            }
                        }
                    }
                }
                is FirLiteralExpression -> {
                    val value = argument.value
                    if (value is String) {
                        packages.add(value)
                    }
                }
            }
        }
        // If no packages specified, use class's package
        if (packages.isEmpty()) {
            packages.add(classSymbol.classId.packageFqName.asString())
        }
        return packages
    }

    // Cache of definition classes (@Singleton, @Factory, @KoinViewModel, etc.) for cross-module discovery
    // Only includes "orphan" definitions - classes NOT covered by any local @Module's @ComponentScan
    // These generate hint functions in org.koin.plugin.hints package
    private val definitionClassInfos: List<DefinitionClassInfo> by lazy {
        val provider = session.predicateBasedProvider
        val definitions = mutableListOf<DefinitionClassInfo>()

        // Helper to collect classes for a predicate with a definition type
        fun collectDefinitions(predicate: LookupPredicate, defType: String) {
            provider.getSymbolsByPredicate(predicate)
                .filterIsInstance<FirClassSymbol<*>>()
                .filter { classSymbol ->
                    // Skip expect classes
                    !classSymbol.rawStatus.isExpect
                }
                .forEach { classSymbol ->
                    val containingFileName = getContainingFileName(classSymbol)
                    if (containingFileName != null) {
                        // Only generate hint if NOT covered by local @ComponentScan
                        val packageName = classSymbol.classId.packageFqName.asString()
                        if (isCoveredByLocalScan(packageName)) {
                            log { "  Skipping @$defType class: ${classSymbol.classId} - covered by local @ComponentScan" }
                        } else {
                            log { "  Found @$defType class: ${classSymbol.classId} (orphan, needs hint)" }
                            logUser { "Exporting @$defType ${classSymbol.classId.shortClassName} for cross-module discovery" }
                            definitions.add(DefinitionClassInfo(classSymbol, defType, containingFileName))
                        }
                    }
                }
        }

        log { "Collecting orphan definition classes for cross-module discovery..." }
        collectDefinitions(singletonPredicate, DEF_TYPE_SINGLE)
        collectDefinitions(singlePredicate, DEF_TYPE_SINGLE)
        collectDefinitions(factoryPredicate, DEF_TYPE_FACTORY)
        collectDefinitions(scopedPredicate, DEF_TYPE_SCOPED)
        collectDefinitions(viewModelPredicate, DEF_TYPE_VIEWMODEL)
        collectDefinitions(workerPredicate, DEF_TYPE_WORKER)
        // Scope archetype annotations imply SCOPED
        collectDefinitions(viewModelScopePredicate, DEF_TYPE_SCOPED)
        collectDefinitions(activityScopePredicate, DEF_TYPE_SCOPED)
        collectDefinitions(activityRetainedScopePredicate, DEF_TYPE_SCOPED)
        collectDefinitions(fragmentScopePredicate, DEF_TYPE_SCOPED)
        // JSR-330 annotations - class-level @Singleton
        collectDefinitions(jakartaSingletonPredicate, DEF_TYPE_SINGLE)
        collectDefinitions(javaxSingletonPredicate, DEF_TYPE_SINGLE)
        // JSR-330: class-level @Inject
        collectDefinitions(jakartaInjectPredicate, DEF_TYPE_FACTORY)
        collectDefinitions(javaxInjectPredicate, DEF_TYPE_FACTORY)

        // Find classes with @Inject constructor (annotation on constructor, not class)
        // FIR predicates can't match constructor annotations, so we use PSI-based scanning
        collectInjectConstructorClasses(definitions)

        log { "Found ${definitions.size} orphan definition classes (need hints for cross-module discovery)" }
        definitions
    }

    // Cache of top-level functions with definition annotations for cross-module discovery
    // Only includes "orphan" functions NOT covered by any local @Module's @ComponentScan
    // These generate function hint functions in org.koin.plugin.hints package
    private val definitionFunctionInfos: List<DefinitionFunctionInfo> by lazy {
        val provider = session.predicateBasedProvider
        val functions = mutableListOf<DefinitionFunctionInfo>()

        fun collectFunctions(predicate: LookupPredicate, defType: String) {
            provider.getSymbolsByPredicate(predicate)
                .filterIsInstance<FirNamedFunctionSymbol>()
                .forEach { functionSymbol ->
                    // Skip functions inside classes (only top-level)
                    val callableId = functionSymbol.callableId
                    if (callableId.classId != null) return@forEach

                    // Get return type ClassId
                    val returnTypeClassId = functionSymbol.resolvedReturnTypeRef.coneType.classId ?: return@forEach

                    // Skip Unit return types (not useful as DI providers)
                    if (returnTypeClassId.asSingleFqName().asString() == "kotlin.Unit") return@forEach

                    val packageName = callableId.packageName.asString()
                    if (isCoveredByLocalScan(packageName)) {
                        log { "  Skipping @$defType function: ${callableId.callableName} - covered by local @ComponentScan" }
                        return@forEach
                    }

                    // Get containing file name
                    val source = functionSymbol.fir.source
                    val containingFileName = when (source) {
                        is KtPsiSourceElement -> source.psi.containingFile?.name
                        else -> {
                            val isRealSource = source?.kind?.toString()?.contains("RealSourceElementKind") == true
                            if (isRealSource) {
                                // Use deterministic synthetic file name based on function's package + name
                                val syntheticName = "${packageName.replace('.', '_')}_${callableId.callableName}_FunctionDefinition.kt"
                                syntheticName
                            } else null
                        }
                    }

                    if (containingFileName != null) {
                        log { "  Found @$defType function: ${callableId.callableName}() -> $returnTypeClassId (orphan, needs hint)" }
                        logUser { "Exporting @$defType function ${callableId.callableName}() for cross-module discovery" }
                        functions.add(DefinitionFunctionInfo(functionSymbol, defType, containingFileName, returnTypeClassId))
                    }
                }
        }

        log { "Collecting orphan definition functions for cross-module discovery..." }
        collectFunctions(singletonPredicate, DEF_TYPE_SINGLE)
        collectFunctions(singlePredicate, DEF_TYPE_SINGLE)
        collectFunctions(factoryPredicate, DEF_TYPE_FACTORY)
        collectFunctions(scopedPredicate, DEF_TYPE_SCOPED)
        collectFunctions(viewModelPredicate, DEF_TYPE_VIEWMODEL)
        collectFunctions(workerPredicate, DEF_TYPE_WORKER)

        log { "Found ${functions.size} orphan definition functions (need hints for cross-module discovery)" }
        functions
    }

    /**
     * Check if a FIR class symbol has a constructor annotated with @Inject (jakarta or javax).
     * Works for both KtPsiSourceElement and KtLightSourceElement sources.
     */
    private fun hasInjectConstructorFir(classSymbol: FirClassSymbol<*>): Boolean {
        return classSymbol.declarationSymbols
            .filterIsInstance<FirConstructorSymbol>()
            .any { constructorSymbol ->
                constructorSymbol.fir.annotations.any { annotation ->
                    val annotationClassId = annotation.annotationTypeRef.coneTypeOrNull?.classId
                    val fqName = annotationClassId?.asSingleFqName()
                    fqName == JAVAX_INJECT_ANNOTATION || fqName == JAKARTA_INJECT_ANNOTATION
                }
            }
    }

    // Predicate to find ALL classes that might have @Inject constructor
    // This predicate matches ANY class - we filter in code for actual @Inject constructors
    // This is necessary because FIR predicates can't detect constructor annotations directly
    private val anyClassPredicate = LookupPredicate.create {
        // Match any class (empty predicate matches nothing, so we use a broad condition)
        // Classes that have @Inject annotation import typically also have other classes
        annotated(JAVAX_INJECT_ANNOTATION) or annotated(JAKARTA_INJECT_ANNOTATION) or
        annotated(JAVAX_SINGLETON_ANNOTATION) or annotated(JAKARTA_SINGLETON_ANNOTATION) or
        // Also match common annotation patterns that indicate a DI-aware module
        annotated(FqName("javax.inject.Named")) or annotated(FqName("jakarta.inject.Named"))
    }

    /**
     * Find classes with @Inject annotated constructor (jakarta.inject.Inject or javax.inject.Inject).
     * The predicate-based search only finds class-level annotations, but @Inject constructor
     * is a common pattern where the annotation is on the constructor.
     *
     * Uses PSI directly to check constructor annotations - more efficient than FIR symbol access.
     * Note: This only finds orphan @Inject classes (not covered by local @ComponentScan).
     * Module-scoped @Inject classes are discovered at IR time and exported via componentscan_* hints.
     */
    private fun collectInjectConstructorClasses(definitions: MutableList<DefinitionClassInfo>) {
        val existingClassIds = definitions.map { it.classSymbol.classId }.toMutableSet()
        val seenFiles = mutableSetOf<String>()
        var foundCount = 0

        // Scan source files from known classes to find @Inject constructor classes
        val knownClasses = moduleClassInfos.map { it.classSymbol } + definitions.map { it.classSymbol }
        for (classSymbol in knownClasses) {
            try {
                val source = classSymbol.fir.source
                if (source is KtPsiSourceElement) {
                    val ktFile = source.psi.containingFile as? org.jetbrains.kotlin.psi.KtFile ?: continue
                    val filePath = ktFile.virtualFilePath
                    if (filePath in seenFiles) continue
                    seenFiles.add(filePath)

                    // Scan all classes in this file using PSI
                    foundCount += scanKtFileForInjectConstructorClasses(ktFile, existingClassIds, definitions)
                }
            } catch (e: Exception) {
                log { "  Error scanning file for @Inject constructor: ${e.message}" }
            }
        }

        // Fallback: When no @Module or definition classes found (standalone @Inject module),
        // try to find source files via the session's lookup table.
        // This handles modules like core:domain that only have @Inject constructor classes.
        if (knownClasses.isEmpty()) {
            log { "  No known classes found, trying to find @Inject constructor classes via lookup..." }
            try {
                // Use the predicate provider to find any classes that might have @Inject constructor
                // We use anyClassPredicate which matches classes with any DI-related annotation
                val provider = session.predicateBasedProvider
                val potentialClasses = provider.getSymbolsByPredicate(anyClassPredicate)

                for (symbol in potentialClasses.filterIsInstance<FirClassSymbol<*>>()) {
                    val source = symbol.fir.source
                    if (source is KtPsiSourceElement) {
                        val ktFile = source.psi.containingFile as? org.jetbrains.kotlin.psi.KtFile ?: continue
                        val filePath = ktFile.virtualFilePath
                        if (filePath in seenFiles) continue
                        seenFiles.add(filePath)

                        foundCount += scanKtFileForInjectConstructorClasses(ktFile, existingClassIds, definitions)
                    }
                }
            } catch (e: Exception) {
                log { "  Error in fallback @Inject constructor scanning: ${e.message}" }
            }
        }

        log { "  Scanned ${seenFiles.size} files, found $foundCount @Inject constructor classes" }
    }

    /**
     * Scan a KtFile for classes with @Inject constructor and add them to definitions.
     * Returns the count of classes found.
     */
    private fun scanKtFileForInjectConstructorClasses(
        ktFile: org.jetbrains.kotlin.psi.KtFile,
        existingClassIds: Set<ClassId>,
        definitions: MutableList<DefinitionClassInfo>
    ): Int {
        var count = 0

        fun hasInjectConstructor(ktClass: org.jetbrains.kotlin.psi.KtClass): Boolean {
            val primaryConstructor = ktClass.primaryConstructor ?: return false
            return primaryConstructor.annotationEntries.any { annotation ->
                val fqName = annotation.shortName?.asString()
                fqName == "Inject" // Matches jakarta.inject.Inject or javax.inject.Inject
            }
        }

        fun scanDeclarations(declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>) {
            for (declaration in declarations) {
                if (declaration is org.jetbrains.kotlin.psi.KtClass && hasInjectConstructor(declaration)) {
                    val fqNameStr = declaration.fqName?.asString() ?: continue
                    try {
                        val classId = ClassId.topLevel(FqName(fqNameStr))
                        if (classId in existingClassIds) continue

                        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId)
                        if (symbol is FirClassSymbol<*> && !symbol.rawStatus.isExpect) {
                            val containingFileName = getContainingFileName(symbol)
                            if (containingFileName != null) {
                                val packageName = classId.packageFqName.asString()
                                if (isCoveredByLocalScan(packageName)) {
                                    log { "  Skipping @Inject constructor class: $classId - covered by local @ComponentScan" }
                                } else {
                                    log { "  Found @Inject constructor class: $classId (orphan, needs hint)" }
                                    logUser { "Exporting @Inject constructor ${classId.shortClassName} for cross-module discovery" }
                                    definitions.add(DefinitionClassInfo(symbol, DEF_TYPE_FACTORY, containingFileName))
                                    count++
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Class might not be resolvable
                    }
                }
                // Also scan nested classes
                if (declaration is org.jetbrains.kotlin.psi.KtClass) {
                    declaration.body?.declarations?.let { nested -> scanDeclarations(nested) }
                }
            }
        }

        scanDeclarations(ktFile.declarations)
        return count
    }

    /**
     * Get containing file name for a class symbol, handling different source types.
     */
    private fun getContainingFileName(classSymbol: FirClassSymbol<*>): String? {
        val source = classSymbol.fir.source
        val sourceKind = source?.kind
        val sourceType = source?.javaClass?.simpleName

        return when (source) {
            is KtPsiSourceElement -> {
                source.psi.containingFile?.name
            }
            else -> {
                // Check if this is a real source element (KtRealSourceElementKind) vs synthetic
                val isRealSource = sourceKind?.toString()?.contains("RealSourceElementKind") == true
                if (isRealSource) {
                    // Use deterministic synthetic file names
                    syntheticFileName(classSymbol.classId, "Definition")
                } else {
                    // Skip classes from dependencies (JARs/metadata)
                    log { "    Skipping class from dependency: $sourceType (kind=$sourceKind)" }
                    null
                }
            }
        }
    }

    /**
     * Log a debug message for FIR phase.
     */
    private inline fun log(message: () -> String) {
        KoinPluginLogger.debugFir(message)
    }

    /**
     * Log a user-facing message for FIR phase.
     */
    private inline fun logUser(message: () -> String) {
        KoinPluginLogger.userFir(message)
    }

    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        log { "registerPredicates: Registering predicates for @Module and definition annotations" }
        register(modulePredicate)
        // Configuration and ComponentScan predicates for KMP-safe annotation detection
        register(configurationPredicate)
        register(componentScanPredicate)
        // Definition predicates for cross-module @ComponentScan discovery
        register(singletonPredicate)
        register(singlePredicate)
        register(factoryPredicate)
        register(scopedPredicate)
        register(viewModelPredicate)
        register(workerPredicate)
        // Scope archetype predicates
        register(viewModelScopePredicate)
        register(activityScopePredicate)
        register(activityRetainedScopePredicate)
        register(fragmentScopePredicate)
        // JSR-330 predicates
        register(jakartaSingletonPredicate)
        register(jakartaInjectPredicate)
        register(javaxSingletonPredicate)
        register(javaxInjectPredicate)
        // Catch-all for @Inject constructor discovery (needed for modules with only @Inject constructor classes)
        register(anyClassPredicate)
        // Note: We can't trigger hint discovery here because symbolNamesProvider isn't ready yet
    }

    @ExperimentalTopLevelDeclarationsGenerationApi
    override fun getTopLevelCallableIds(): Set<CallableId> {
        // Note: We cannot call symbolProvider here because it would create infinite recursion
        // The symbol provider needs to call getTopLevelCallableIds() on all extensions to build the names

        val callableIds = mutableSetOf<CallableId>()

        // Generate module extension functions for @Module classes
        // These are needed for cross-module references (startKoin finding modules from dependencies)
        moduleClasses.forEach { classSymbol ->
            val packageFqName = classSymbol.classId.packageFqName
            callableIds.add(CallableId(packageFqName, MODULE_FUNCTION_NAME))
        }

        // Generate hint functions for @Configuration modules (cross-module discovery)
        // One function per label per configuration module in the hints package
        // Example: @Configuration("test", "prod") generates configuration_test and configuration_prod
        // Hint functions use @Deprecated(HIDDEN) to prevent ObjC export on Native targets
        val allLabels = mutableSetOf<String>()
        configurationModules.forEach { configModule ->
            allLabels.addAll(configModule.labels)
        }

        for (label in allLabels) {
            callableIds.add(CallableId(HINTS_PACKAGE, hintFunctionNameForLabel(label)))
        }

        // Always include hints package with "default" label to trigger generateFunctions()
        // for hint discovery. This ensures cross-module discovery happens even when there are no local
        // @Module classes (e.g., test source sets discovering modules from main source sets)
        callableIds.add(CallableId(HINTS_PACKAGE, hintFunctionNameForLabel(KoinPluginConstants.DEFAULT_LABEL)))

        // Generate hint functions for definition classes and functions (cross-module discovery)
        // Always include all definition types to trigger discovery from dependencies
        for (defType in ALL_DEFINITION_TYPES) {
            callableIds.add(CallableId(HINTS_PACKAGE, definitionHintFunctionName(defType)))
            callableIds.add(CallableId(HINTS_PACKAGE, definitionFunctionHintFunctionName(defType)))
        }

        // Note: componentscan_* / componentscanfunc_* hints are now generated at IR time
        // using registerFunctionAsMetadataVisible (see KoinAnnotationProcessor.generateModuleScanHints).
        // This ensures complete discovery of @Inject constructor classes in subpackages.

        log { "getTopLevelCallableIds() returning ${callableIds.size} callables (${moduleClasses.size} modules, definitions=${definitionClassInfos.size}, functionDefs=${definitionFunctionInfos.size})" }
        return callableIds
    }

    /**
     * Generate module extension functions and hint functions.
     * Uses containingFileName (Kotlin 2.3.0+) to place functions in the source file,
     * avoiding synthetic files that break Kotlin/Native compilation.
     *
     * Note: Using functions instead of properties avoids NPE on Kotlin/Native due to backing field issues.
     */
    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?
    ): List<FirNamedFunctionSymbol> {
        // Generate hint functions for @Configuration modules
        // Function name is label-specific: configuration_<label>
        if (callableId.packageName == HINTS_PACKAGE) {
            val label = labelFromHintFunctionName(callableId.callableName.asString())
            if (label != null) {
                // Find modules that have this label
                val modulesWithLabel = configurationModules.filter { it.labels.contains(label) }
                log { "generateFunctions: Generating hint functions for label '$label', ${modulesWithLabel.size} modules" }

                return modulesWithLabel.mapNotNull { configModule ->
                    val classSymbol = configModule.classSymbol
                    val moduleType = classSymbol.constructType(emptyArray(), false)

                    // Use pre-captured file name from ConfigurationModule (captured during discovery)
                    val containingFile = configModule.containingFileName

                    // Use pre-captured file name, or deterministic synthetic file name for dependencies
                    val effectiveFileName = containingFile ?: syntheticFileName(classSymbol.classId, "Configuration")

                    log { "  -> Generating hint for ${classSymbol.classId} with label '$label' in file $effectiveFileName" }

                    createTopLevelFunction(
                        Key,
                        callableId,
                        session.builtinTypes.unitType.coneType,
                        containingFileName = effectiveFileName
                    ) {
                        valueParameter(Name.identifier("contributed"), moduleType)
                    }.apply { markAsDeprecatedHidden() }.symbol
                }
            }
        }

        // Generate module extension functions for @Module classes
        if (callableId.callableName == MODULE_FUNCTION_NAME) {
            // Find module classes in this package - use moduleClassInfos to access pre-captured source file info
            val matchingModules = moduleClassInfos.filter { info ->
                info.classSymbol.classId.packageFqName == callableId.packageName
            }

            if (matchingModules.isEmpty()) {
                return emptyList()
            }

            log { "generateFunctions: Generating ${matchingModules.size} module functions in ${callableId.packageName}" }

            return matchingModules.mapNotNull { moduleInfo ->
                val classSymbol = moduleInfo.classSymbol
                val moduleType = KOIN_MODULE_CLASS_ID.constructClassLikeType(emptyArray(), false)
                val extensionType = classSymbol.constructType(emptyArray(), false)
                val functionCallableId = CallableId(classSymbol.classId.packageFqName, MODULE_FUNCTION_NAME)

                // Use pre-captured source file info from discovery phase
                // This is important for KMP where source type changes between phases
                val containingFile = moduleInfo.containingFileName
                val isActual = classSymbol.rawStatus.isActual
                log { "  -> Source for ${classSymbol.classId}: containingFile=$containingFile, isActual=$isActual" }

                // Use source file if available, otherwise use deterministic synthetic file name
                val effectiveFileName = containingFile ?: syntheticFileName(classSymbol.classId, "Module")

                log { "  -> Generating module() for ${classSymbol.classId} in file $effectiveFileName" }

                createTopLevelFunction(
                    Key,
                    functionCallableId,
                    moduleType,
                    containingFileName = effectiveFileName
                ) {
                    extensionReceiverType(extensionType)
                }.symbol
            }
        }

        // Generate definition hint functions for cross-module @ComponentScan discovery
        // Function name format: definition_<type> (e.g., definition_single, definition_viewmodel)
        if (callableId.packageName == HINTS_PACKAGE) {
            val defType = definitionTypeFromHintFunctionName(callableId.callableName.asString())
            if (defType != null) {
                // Find definition classes with this type
                val matchingDefinitions = definitionClassInfos.filter { it.definitionType == defType }
                log { "generateFunctions: Generating definition hints for type '$defType', ${matchingDefinitions.size} classes" }

                return matchingDefinitions.mapNotNull { defInfo ->
                    val classSymbol = defInfo.classSymbol
                    val classType = classSymbol.constructType(emptyArray(), false)

                    val containingFile = defInfo.containingFileName

                    // Skip synthetic file generation for dependency classes
                    if (containingFile == null) {
                        log { "  -> Skipping definition hint for ${classSymbol.classId} (no source file)" }
                        return@mapNotNull null
                    }

                    val effectiveFileName = containingFile

                    log { "  -> Generating definition hint for ${classSymbol.classId} (type=$defType) in file $effectiveFileName" }

                    createTopLevelFunction(
                        Key,
                        callableId,
                        session.builtinTypes.unitType.coneType,
                        containingFileName = effectiveFileName
                    ) {
                        // visibility stays public for cross-module discovery
                        valueParameter(Name.identifier("contributed"), classType)
                    }.apply { markAsDeprecatedHidden() }.symbol
                }
            }

            // Generate function definition hint functions for cross-module top-level function discovery
            // Function name format: definition_function_<type> (e.g., definition_function_single)
            // The hint carries the return type as the parameter type (what the function provides)
            val funcDefType = definitionTypeFromFunctionHintName(callableId.callableName.asString())
            if (funcDefType != null) {
                val matchingFunctions = definitionFunctionInfos.filter { it.definitionType == funcDefType }
                log { "generateFunctions: Generating function definition hints for type '$funcDefType', ${matchingFunctions.size} functions" }

                return matchingFunctions.mapNotNull { funcInfo ->
                    val containingFile = funcInfo.containingFileName
                    if (containingFile == null) {
                        log { "  -> Skipping function definition hint for ${funcInfo.functionSymbol.callableId} (no source file)" }
                        return@mapNotNull null
                    }

                    // Build the return type as a class type for the hint parameter
                    val returnClassType = funcInfo.returnTypeClassId.constructClassLikeType(emptyArray(), false)

                    log { "  -> Generating function definition hint for ${funcInfo.functionSymbol.callableId.callableName}() -> ${funcInfo.returnTypeClassId} (type=$funcDefType) in file $containingFile" }

                    createTopLevelFunction(
                        Key,
                        callableId,
                        session.builtinTypes.unitType.coneType,
                        containingFileName = containingFile
                    ) {
                        // visibility stays public for cross-module discovery
                        valueParameter(Name.identifier("contributed"), returnClassType)
                    }.apply { markAsDeprecatedHidden() }.symbol
                }
            }

            // Note: componentscan_* / componentscanfunc_* hints are now generated at IR time
            // using registerFunctionAsMetadataVisible (see KoinAnnotationProcessor.generateModuleScanHints).
        }

        return emptyList()
    }

    /**
     * Claim ownership of the hints package for generated hint functions.
     */
    override fun hasPackage(packageFqName: FqName): Boolean {
        if (packageFqName == HINTS_PACKAGE && (configurationModules.isNotEmpty() || definitionClassInfos.isNotEmpty() || definitionFunctionInfos.isNotEmpty())) {
            return true
        }
        return super.hasPackage(packageFqName)
    }

    object Key : GeneratedDeclarationKey() {
        override fun toString(): String = "KoinModuleGeneratedKey"
    }
}
