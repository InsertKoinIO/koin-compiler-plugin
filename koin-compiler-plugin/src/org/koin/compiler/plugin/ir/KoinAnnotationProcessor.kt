package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.builder.buildPackageDirective
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.declarations.builder.buildFile
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.ProvidedTypeRegistry
import org.koin.compiler.plugin.PropertyValueRegistry
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

/**
 * Processes Koin annotations and generates module extension properties with definitions.
 *
 * Supports:
 * - @Singleton/@Single/@Factory/@Scoped on classes
 * - @KoinViewModel/@KoinWorker on classes
 * - Definition functions inside @Module classes
 * - Auto-binding for interfaces + explicit binds parameter
 * - createdAtStart parameter
 * - @Named/@Qualifier qualifiers
 * - @InjectedParam for parametersOf() injection
 * - @Property/@PropertyValue for property injection
 * - List<T> dependencies via getAll()
 * - Scope archetypes (@ViewModelScope, @ActivityScope, etc.)
 * - JSR-330 compatibility (jakarta.inject.* and javax.inject.*)
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class KoinAnnotationProcessor(
    private val context: IrPluginContext,
    private val qualifierExtractor: QualifierExtractor,
    private val safetyValidator: CompileSafetyValidator? = null,
    private val lookupTracker: LookupTracker? = null,
    private val expectActualTracker: ExpectActualTracker? = null
) {

    // Argument generator for lambda parameters
    private val argumentGenerator = KoinArgumentGenerator(context, qualifierExtractor)

    // Lambda builder helper
    private val lambdaBuilder = LambdaBuilder(context, qualifierExtractor, argumentGenerator)

    // Scope block builder helper
    private val scopeBlockBuilder = ScopeBlockBuilder(context)

    // Definition call builder helper
    private val definitionCallBuilder = DefinitionCallBuilder(context, qualifierExtractor, lambdaBuilder, argumentGenerator)

    // Cached class lookups (avoid repeated context.referenceClass calls)
    private val kClassClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KCLASS))?.owner }
    private val function1Class by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.FUNCTION1))?.owner }
    private val function2Class by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.FUNCTION2))?.owner }
    private val scopeDslClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.SCOPE_DSL))?.owner }
    private val koinModuleClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KOIN_MODULE))?.owner }
    private val scopeClassCached by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.SCOPE_CLASS))?.owner }
    private val parametersHolderClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.PARAMETERS_HOLDER))?.owner }
    private val lazyModeClass by lazy { context.referenceClass(ClassId.topLevel(FqName("kotlin.LazyThreadSafetyMode"))) }
    private val koinModuleClassSymbol by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KOIN_MODULE)) }

    // Collected data (types defined in AnnotationModels.kt)
    private val moduleClasses = mutableListOf<ModuleClass>()
    private val definitionClasses = mutableListOf<DefinitionClass>()
    private val definitionTopLevelFunctions = mutableListOf<DefinitionTopLevelFunction>()

    /** Exposed for cross-phase validation (A3: startKoin full-graph). */
    val collectedModuleClasses: List<ModuleClass> get() = moduleClasses

    /**
     * Get all definitions for a module (local + cross-module via hints + included modules).
     * Returns a DependencyModuleResult with isComplete=false when any included dependency module's
     * definitions couldn't be fully resolved (e.g., when hint functions are unavailable).
     */
    fun getDefinitionsForModule(moduleClass: ModuleClass): DependencyModuleResult {
        val definitions = (cachedModuleDefinitions?.get(moduleClass) ?: collectAllDefinitions(moduleClass)).toMutableList()
        var allComplete = true

        // Track visited modules to prevent infinite recursion on circular includes
        val visited = mutableSetOf<String>()
        moduleClass.irClass.fqNameWhenAvailable?.asString()?.let { visited.add(it) }

        // Follow @Module(includes = [...]) to collect included module definitions.
        // Included modules may not be @Configuration, so they won't appear in the top-level
        // module list. We must collect their definitions here for A3 full-graph validation.
        val existingFqNames = definitions.mapNotNull { it.returnTypeClass.fqNameWhenAvailable }.toMutableSet()
        for (included in moduleClass.includedModules) {
            val includedFqName = included.fqNameWhenAvailable?.asString() ?: continue
            val localIncluded = collectedModuleClasses.find {
                it.irClass.fqNameWhenAvailable == included.fqNameWhenAvailable
            }
            if (localIncluded != null) {
                val newDefs = collectAllDefinitions(localIncluded).filter { it.returnTypeClass.fqNameWhenAvailable !in existingFqNames }
                definitions.addAll(newDefs)
                existingFqNames.addAll(newDefs.mapNotNull { it.returnTypeClass.fqNameWhenAvailable })
            } else {
                val result = collectDefinitionsFromDependencyModule(includedFqName, visited)
                if (!result.isComplete) allComplete = false
                val newDefs = result.definitions.filter { it.returnTypeClass.fqNameWhenAvailable !in existingFqNames }
                definitions.addAll(newDefs)
                existingFqNames.addAll(newDefs.mapNotNull { it.returnTypeClass.fqNameWhenAvailable })
            }
        }

        return DependencyModuleResult(definitions, allComplete)
    }

    /** Get all definitions across all modules (for A4 call-site validation). */
    fun getAllKnownDefinitions(): List<Definition> {
        return collectedModuleClasses.flatMap { moduleClass ->
            getDefinitionsForModule(moduleClass).definitions
        }
    }

    /** Get definitions from a dependency JAR module (for cross-module validation). */
    internal fun getDefinitionsForDependencyModule(moduleFqName: String): DependencyModuleResult {
        return collectDefinitionsFromDependencyModule(moduleFqName)
    }

    /**
     * Phase 1: Collect all annotated classes, functions, and property values
     */
    fun collectAnnotations(moduleFragment: IrModuleFragment) {
        // Clear registries for fresh compilation
        PropertyValueRegistry.clear()
        ProvidedTypeRegistry.clear()

        moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                processClass(declaration)
                super.visitClass(declaration)
            }

            override fun visitProperty(declaration: IrProperty) {
                processPropertyValue(declaration)
                super.visitProperty(declaration)
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                // Only process top-level functions (parent is IrFile, not IrClass)
                if (declaration.parent is IrFile) {
                    processTopLevelFunction(declaration)
                }
                super.visitSimpleFunction(declaration)
            }
        })
    }

    /**
     * Process @PropertyValue annotations on properties.
     * Registers the property as a default value provider for the given key.
     *
     * Note: In Kotlin IR, annotations can be on the property, backing field, or getter.
     * We check all locations for the @PropertyValue annotation.
     */
    private fun processPropertyValue(declaration: IrProperty) {
        // Check property annotations
        var propertyValueAnnotation = declaration.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.PROPERTY_VALUE.asString()
        }

        // Also check backing field annotations (annotations might be stored there)
        if (propertyValueAnnotation == null) {
            propertyValueAnnotation = declaration.backingField?.annotations?.firstOrNull { annotation ->
                annotation.type.classFqName?.asString() == KoinAnnotationFqNames.PROPERTY_VALUE.asString()
            }
        }

        // Also check getter annotations
        if (propertyValueAnnotation == null) {
            propertyValueAnnotation = declaration.getter?.annotations?.firstOrNull { annotation ->
                annotation.type.classFqName?.asString() == KoinAnnotationFqNames.PROPERTY_VALUE.asString()
            }
        }

        if (propertyValueAnnotation == null) return

        // Extract the property key from @PropertyValue("key")
        val valueArg = propertyValueAnnotation.getValueArgument(0)
        val propertyKey = (valueArg as? IrConst)?.value as? String ?: return

        // Register this property as the default value provider
        PropertyValueRegistry.register(propertyKey, declaration)

        if (KoinPluginLogger.userLogsEnabled) {
            KoinPluginLogger.user { "@PropertyValue(\"$propertyKey\") on property ${declaration.name}" }
        }
    }

    private fun processClass(declaration: IrClass) {
        val hasModule = declaration.hasAnnotation(KoinAnnotationFqNames.MODULE)
        val hasComponentScan = declaration.hasAnnotation(KoinAnnotationFqNames.COMPONENT_SCAN)

        if (hasModule) {
            // @Module with or without @ComponentScan
            val scanPackages = if (hasComponentScan) getComponentScanPackages(declaration) else emptyList()
            val definitionFunctions = collectDefinitionFunctions(declaration)
            val includedModules = getModuleIncludes(declaration)
            moduleClasses.add(ModuleClass(declaration, hasComponentScan, scanPackages, definitionFunctions, includedModules))

            // Log module discovery (guard to avoid precomputation when logging is disabled)
            if (KoinPluginLogger.userLogsEnabled) {
                if (hasComponentScan) {
                    if (scanPackages.isNotEmpty()) {
                        // Specified packages: @ComponentScan("pkg1", "pkg2")
                        KoinPluginLogger.user { "@Module/@ComponentScan(\"${scanPackages.joinToString("\", \"")}\") on class ${declaration.name}" }
                    } else {
                        // No args: @ComponentScan - will use current package
                        KoinPluginLogger.user { "@Module/@ComponentScan on class ${declaration.name}" }
                    }
                } else {
                    KoinPluginLogger.user { "@Module on class ${declaration.name}" }
                }
                if (includedModules.isNotEmpty()) {
                    KoinPluginLogger.user { "  Includes: ${includedModules.joinToString(", ") { it.name.asString() }}" }
                }
                if (definitionFunctions.isNotEmpty()) {
                    definitionFunctions.forEach { defFunc ->
                        val annotationName = getDefinitionAnnotationName(defFunc.irFunction) ?: defFunc.definitionType.name
                        KoinPluginLogger.user { "  @$annotationName on function ${defFunc.irFunction.name}() -> ${defFunc.returnTypeClass.name}" }
                    }
                }
            }
        }

        // Check for @Provided annotation — marks a type as externally available
        if (declaration.hasAnnotation(KoinAnnotationFqNames.PROVIDED)) {
            val fqName = declaration.fqNameWhenAvailable?.asString()
            if (fqName != null) {
                ProvidedTypeRegistry.register(fqName)
                if (KoinPluginLogger.userLogsEnabled) {
                    KoinPluginLogger.user { "@Provided on class ${declaration.name}" }
                }
            }
        }

        // Check for definition annotations on class
        val definitionType = getDefinitionType(declaration)
        // Check for scope archetype annotations (@ViewModelScope, @ActivityScope, etc.)
        val scopeArchetype = getScopeArchetype(declaration)

        // Skip expect classes - only process actual classes (expect classes can't be instantiated)
        if (declaration.isExpect) {
            return
        }


        if (definitionType != null || scopeArchetype != null) {
            val packageFqName = declaration.packageFqName ?: FqName.ROOT
            // Combine auto-detected bindings with explicit bindings from annotation
            val autoBindings = detectBindings(declaration)
            val explicitBindings = getExplicitBindings(declaration)
            val bindings = (explicitBindings + autoBindings).distinctBy { it.fqNameWhenAvailable }
            val scopeClass = getScopeClass(declaration)
            val createdAtStart = getCreatedAtStart(declaration)
            // If scope archetype is present but no definition type, default to SCOPED
            val finalDefinitionType = definitionType ?: DefinitionType.SCOPED
            definitionClasses.add(DefinitionClass(
                declaration, finalDefinitionType, packageFqName, bindings,
                scopeClass, scopeArchetype, createdAtStart
            ))

            logDefinitionDiscovery(declaration, finalDefinitionType, scopeArchetype, scopeClass, bindings, createdAtStart, "class ${declaration.name}")
        }
    }

    /**
     * Process top-level functions with definition annotations (@Singleton, @Factory, etc.)
     * These are treated like annotated classes and can be discovered by @ComponentScan.
     */
    private fun processTopLevelFunction(declaration: IrSimpleFunction) {
        val definitionType = getDefinitionType(declaration) ?: return
        val returnType = declaration.returnType
        val returnTypeClass = (returnType.classifierOrNull?.owner as? IrClass) ?: return

        val packageFqName = (declaration.parent as? IrFile)?.packageFqName ?: FqName.ROOT
        val bindings = getExplicitBindings(declaration)
        val scopeClass = getScopeClass(declaration)
        val scopeArchetype = getScopeArchetype(declaration)
        val createdAtStart = getCreatedAtStart(declaration)

        definitionTopLevelFunctions.add(DefinitionTopLevelFunction(
            declaration, definitionType, packageFqName, returnTypeClass,
            bindings, scopeClass, scopeArchetype, createdAtStart
        ))

        logDefinitionDiscovery(declaration, definitionType, scopeArchetype, scopeClass, bindings, createdAtStart, "function ${declaration.name}() -> ${returnTypeClass.name}")
    }

    /**
     * Get the actual annotation name used on the declaration
     */
    private fun getDefinitionAnnotationName(declaration: IrDeclaration): String? {
        return when {
            declaration.hasAnnotation(KoinAnnotationFqNames.SINGLETON) -> "Singleton"
            declaration.hasAnnotation(KoinAnnotationFqNames.SINGLE) -> "Single"
            declaration.hasAnnotation(KoinAnnotationFqNames.FACTORY) -> "Factory"
            declaration.hasAnnotation(KoinAnnotationFqNames.SCOPED) -> "Scoped"
            declaration.hasAnnotation(KoinAnnotationFqNames.KOIN_VIEW_MODEL) -> "KoinViewModel"
            declaration.hasAnnotation(KoinAnnotationFqNames.KOIN_WORKER) -> "KoinWorker"
            // Scope archetype annotations
            declaration.hasAnnotation(KoinAnnotationFqNames.VIEW_MODEL_SCOPE) -> "ViewModelScope"
            declaration.hasAnnotation(KoinAnnotationFqNames.ACTIVITY_SCOPE) -> "ActivityScope"
            declaration.hasAnnotation(KoinAnnotationFqNames.ACTIVITY_RETAINED_SCOPE) -> "ActivityRetainedScope"
            declaration.hasAnnotation(KoinAnnotationFqNames.FRAGMENT_SCOPE) -> "FragmentScope"
            declaration.hasAnnotation(KoinAnnotationFqNames.JAKARTA_SINGLETON) -> "jakarta.inject.Singleton"
            declaration.hasAnnotation(KoinAnnotationFqNames.JAKARTA_INJECT) -> "jakarta.inject.Inject"
            declaration.hasAnnotation(KoinAnnotationFqNames.JAVAX_SINGLETON) -> "javax.inject.Singleton"
            declaration.hasAnnotation(KoinAnnotationFqNames.JAVAX_INJECT) -> "javax.inject.Inject"
            // JSR-330: @Inject on constructor
            declaration is IrClass && hasInjectConstructor(declaration) -> "Inject constructor"
            else -> null
        }
    }

    /** Log definition annotation discovery details at user log level. */
    private fun logDefinitionDiscovery(
        declaration: IrDeclaration,
        definitionType: DefinitionType,
        scopeArchetype: ScopeArchetype?,
        scopeClass: IrClass?,
        bindings: List<IrClass>,
        createdAtStart: Boolean,
        targetName: String? = null
    ) {
        if (!KoinPluginLogger.userLogsEnabled) return
        val annotationName = getDefinitionAnnotationName(declaration) ?: definitionType.name
        val name = targetName ?: (declaration as? IrDeclarationWithName)?.name?.asString() ?: "unknown"
        KoinPluginLogger.user { "@$annotationName on $name" }
        if (scopeArchetype != null) {
            KoinPluginLogger.user { "  Scope archetype: @${scopeArchetype.name}" }
        }
        if (scopeClass != null) {
            KoinPluginLogger.user { "  @Scope(${scopeClass.name}::class)" }
        }
        if (bindings.isNotEmpty()) {
            KoinPluginLogger.user { "  Binds: ${bindings.joinToString(", ") { it.name.asString() }}" }
        }
        if (createdAtStart) {
            KoinPluginLogger.user { "  createdAtStart = true" }
        }
        val qualifier = qualifierExtractor.extractFromDeclaration(declaration)
        when (qualifier) {
            is QualifierValue.StringQualifier -> KoinPluginLogger.user { "  @Named(\"${qualifier.name}\")" }
            is QualifierValue.TypeQualifier -> KoinPluginLogger.user { "  @Qualifier(${qualifier.irClass.name}::class)" }
            null -> {}
        }
    }

    /**
     * Get the scope class from @Scope(MyScope::class) annotation.
     * Works on both classes and functions.
     */
    private fun getScopeClass(declaration: IrDeclaration): IrClass? {
        val scopeAnnotation = declaration.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.SCOPE.asString()
        } ?: return null

        val valueArg = scopeAnnotation.getValueArgument(0)
        return when (valueArg) {
            is IrClassReferenceImpl -> valueArg.classType.classifierOrNull?.owner as? IrClass
            else -> null
        }
    }

    private fun getDefinitionType(declaration: IrDeclaration): DefinitionType? {
        return when {
            // Koin annotations
            declaration.hasAnnotation(KoinAnnotationFqNames.SINGLETON) -> DefinitionType.SINGLE
            declaration.hasAnnotation(KoinAnnotationFqNames.SINGLE) -> DefinitionType.SINGLE
            declaration.hasAnnotation(KoinAnnotationFqNames.FACTORY) -> DefinitionType.FACTORY
            declaration.hasAnnotation(KoinAnnotationFqNames.SCOPED) -> DefinitionType.SCOPED
            declaration.hasAnnotation(KoinAnnotationFqNames.KOIN_VIEW_MODEL) -> DefinitionType.VIEW_MODEL
            declaration.hasAnnotation(KoinAnnotationFqNames.KOIN_WORKER) -> DefinitionType.WORKER
            // Scope archetype annotations imply SCOPED definition
            declaration.hasAnnotation(KoinAnnotationFqNames.VIEW_MODEL_SCOPE) -> DefinitionType.SCOPED
            declaration.hasAnnotation(KoinAnnotationFqNames.ACTIVITY_SCOPE) -> DefinitionType.SCOPED
            declaration.hasAnnotation(KoinAnnotationFqNames.ACTIVITY_RETAINED_SCOPE) -> DefinitionType.SCOPED
            declaration.hasAnnotation(KoinAnnotationFqNames.FRAGMENT_SCOPE) -> DefinitionType.SCOPED
            // JSR-330 annotations (jakarta.inject)
            declaration.hasAnnotation(KoinAnnotationFqNames.JAKARTA_SINGLETON) -> DefinitionType.SINGLE
            declaration.hasAnnotation(KoinAnnotationFqNames.JAKARTA_INJECT) -> DefinitionType.FACTORY // @Inject on class generates factory
            // JSR-330 annotations (javax.inject) - legacy package
            declaration.hasAnnotation(KoinAnnotationFqNames.JAVAX_SINGLETON) -> DefinitionType.SINGLE
            declaration.hasAnnotation(KoinAnnotationFqNames.JAVAX_INJECT) -> DefinitionType.FACTORY // @Inject on class generates factory
            // JSR-330: @Inject on constructor also generates factory
            declaration is IrClass && hasInjectConstructor(declaration) -> DefinitionType.FACTORY
            else -> null
        }
    }

    /**
     * Check if the class has a constructor annotated with @Inject (jakarta.inject or javax.inject)
     */
    private fun hasInjectConstructor(irClass: IrClass): Boolean {
        return irClass.declarations
            .filterIsInstance<IrConstructor>()
            .any { constructor ->
                constructor.annotations.any { annotation ->
                    val fqName = annotation.type.classFqName?.asString()
                    fqName == KoinAnnotationFqNames.JAKARTA_INJECT.asString() || fqName == KoinAnnotationFqNames.JAVAX_INJECT.asString()
                }
            }
    }

    /**
     * Get scope archetype from annotations like @ViewModelScope, @ActivityScope, etc.
     * Works on both classes and functions.
     */
    private fun getScopeArchetype(declaration: IrDeclaration): ScopeArchetype? {
        return when {
            declaration.hasAnnotation(KoinAnnotationFqNames.VIEW_MODEL_SCOPE) -> ScopeArchetype.VIEW_MODEL_SCOPE
            declaration.hasAnnotation(KoinAnnotationFqNames.ACTIVITY_SCOPE) -> ScopeArchetype.ACTIVITY_SCOPE
            declaration.hasAnnotation(KoinAnnotationFqNames.ACTIVITY_RETAINED_SCOPE) -> ScopeArchetype.ACTIVITY_RETAINED_SCOPE
            declaration.hasAnnotation(KoinAnnotationFqNames.FRAGMENT_SCOPE) -> ScopeArchetype.FRAGMENT_SCOPE
            else -> null
        }
    }

    /**
     * Get createdAtStart parameter from @Single or @Singleton annotation
     */
    private fun getCreatedAtStart(declaration: IrDeclaration): Boolean {
        val annotation = declaration.annotations.firstOrNull { annotation ->
            val fqName = annotation.type.classFqName?.asString()
            fqName == KoinAnnotationFqNames.SINGLETON.asString() || fqName == KoinAnnotationFqNames.SINGLE.asString()
        } ?: return false

        // createdAtStart: look up by name first, then fall back to positional index 1
        val createdAtStartArg = annotation.getValueArgument(Name.identifier("createdAtStart"))
            ?: annotation.getValueArgument(1)

        return when (createdAtStartArg) {
            is IrConst -> createdAtStartArg.value as? Boolean ?: false
            else -> false
        }
    }

    /**
     * Get explicit bindings from @Single(binds = [...]) or @Factory(binds = [...])
     */
    private fun getExplicitBindings(declaration: IrDeclaration): List<IrClass> {
        val definitionAnnotations = listOf(KoinAnnotationFqNames.SINGLETON, KoinAnnotationFqNames.SINGLE, KoinAnnotationFqNames.FACTORY, KoinAnnotationFqNames.SCOPED, KoinAnnotationFqNames.KOIN_VIEW_MODEL, KoinAnnotationFqNames.KOIN_WORKER)

        val annotation = declaration.annotations.firstOrNull { annotation ->
            definitionAnnotations.any { it.asString() == annotation.type.classFqName?.asString() }
        } ?: return emptyList()

        // binds: look up by name first, then fall back to positional index 0
        val bindsArg = annotation.getValueArgument(Name.identifier("binds"))
            ?: annotation.getValueArgument(0)

        if (bindsArg is IrVararg) {
            return bindsArg.elements.mapNotNull { element ->
                when (element) {
                    is IrClassReference -> element.classType.classifierOrNull?.owner as? IrClass
                    else -> null
                }
            }.filter {
                // Filter out Unit::class which is the default value
                it.fqNameWhenAvailable?.asString() != "kotlin.Unit"
            }
        }

        return emptyList()
    }

    /**
     * Custom hasAnnotation that resolves via [IrType.classFqName] (string comparison).
     * The stdlib [org.jetbrains.kotlin.ir.util.hasAnnotation] uses `symbol.owner.classId`
     * which may not resolve correctly for all IR constructs when deprecated-for-removal APIs
     * are involved. This version is safer for our annotation matching needs.
     */
    private fun IrDeclaration.hasAnnotation(fqName: FqName): Boolean {
        return annotations.any { annotation ->
            annotation.type.classFqName?.asString() == fqName.asString()
        }
    }

    /**
     * Collect functions inside @Module class that have definition annotations
     */
    private fun collectDefinitionFunctions(moduleClass: IrClass): List<DefinitionFunction> {
        return moduleClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .mapNotNull { function ->
                val defType = getDefinitionType(function) ?: return@mapNotNull null
                val returnType = function.returnType
                val returnTypeClass = (returnType.classifierOrNull?.owner as? IrClass) ?: return@mapNotNull null
                val scopeClass = getScopeClass(function)
                val scopeArchetype = getScopeArchetype(function)
                val createdAtStart = getCreatedAtStart(function)
                DefinitionFunction(function, defType, returnTypeClass, scopeClass, scopeArchetype, createdAtStart)
            }
    }

    /**
     * Detect interfaces/superclasses that should be auto-bound
     */
    private fun detectBindings(declaration: IrClass): List<IrClass> = detectAutoBindings(declaration)

    private fun getComponentScanPackages(declaration: IrClass): List<String> {
        val annotation = declaration.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.COMPONENT_SCAN.asString()
        } ?: return emptyList()

        val valueArg = annotation.getValueArgument(0)
        if (valueArg is IrVararg) {
            return valueArg.elements.mapNotNull { element ->
                when (element) {
                    is IrConst -> element.value as? String
                    else -> null
                }
            }
        }

        return emptyList()
    }

    /**
     * Parse @Module(includes = [ModuleA::class, ModuleB::class])
     */
    private fun getModuleIncludes(declaration: IrClass): List<IrClass> {
        val annotation = declaration.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.MODULE.asString()
        } ?: return emptyList()

        // Find the "includes" parameter (usually index 0 for @Module)
        val includesArg = annotation.getValueArgument(0)
        if (includesArg is IrVararg) {
            return includesArg.elements.mapNotNull { element ->
                when (element) {
                    is IrClassReferenceImpl -> element.classType.classifierOrNull?.owner as? IrClass
                    else -> null
                }
            }
        }

        return emptyList()
    }

    // Store module fragment for later use in buildIncludesCall
    private var currentModuleFragment: IrModuleFragment? = null

    // Cache for collectAllDefinitions results (populated in generateModuleExtensions, reused in getDefinitionsForModule)
    private var cachedModuleDefinitions: Map<ModuleClass, List<Definition>>? = null

    /**
     * Phase 2: Generate module extension functions in IR
     *
     * Two-pass approach:
     * 1. First pass: Find FIR-generated functions or create new ones (so they can be found by includes())
     * 2. Second pass: Fill all function bodies (now that all functions exist)
     *
     * Note: FIR generates function declarations for cross-module visibility (so startKoin<T> can find modules
     * from dependencies), and IR fills their bodies with actual module definitions.
     */
    fun generateModuleExtensions(moduleFragment: IrModuleFragment) {
        currentModuleFragment = moduleFragment
        KoinPluginLogger.debug { "generateModuleExtensions: ${moduleClasses.size} module classes" }

        // Step 1: Pre-collect definitions for every module
        val moduleDefinitions = moduleClasses.associateWith { collectAllDefinitions(it) }
        cachedModuleDefinitions = moduleDefinitions

        // Step 1b: Generate module-scoped component scan hints for downstream visibility.
        // This replaces the FIR-time generation: IR has complete knowledge of all definitions
        // (including @Inject constructor classes in subpackages that FIR couldn't discover).
        // Uses registerFunctionAsMetadataVisible to make hints visible to downstream compilations.
        generateModuleScanHints(moduleFragment, moduleDefinitions)

        // Map to track functions for each module class (FIR-generated or newly created)
        val moduleFunctions = mutableMapOf<ModuleClass, IrSimpleFunction>()

        // Pre-compute which modules are included by other modules (for A2 validation skip).
        // A module included by another will be validated as part of the parent's visibility set.
        val includedModuleFqNames = mutableSetOf<FqName?>()
        for (mc in moduleClasses) {
            for (included in mc.includedModules) {
                includedModuleFqNames.add(included.fqNameWhenAvailable)
            }
        }

        // Step 2: For each module, build visibility + validate + generate
        for (moduleClass in moduleClasses) {
            val definitions = moduleDefinitions[moduleClass] ?: emptyList()

            // Log module → definitions summary (guard to avoid precomputation when logging is disabled)
            if (KoinPluginLogger.userLogsEnabled && (definitions.isNotEmpty() || moduleClass.includedModules.isNotEmpty())) {
                val moduleName = moduleClass.irClass.name.asString()
                KoinPluginLogger.user { "$moduleName.module() content:" }

                // Log included modules
                if (moduleClass.includedModules.isNotEmpty()) {
                    val includes = moduleClass.includedModules.joinToString(", ") { it.name.asString() }
                    KoinPluginLogger.user { "  includes: $includes" }
                }

                // Log definitions
                definitions.forEach { def ->
                    val defName = when (def) {
                        is Definition.ClassDef -> def.irClass.name.asString()
                        is Definition.FunctionDef -> "${def.irFunction.name}() -> ${def.returnTypeClass.name}"
                        is Definition.TopLevelFunctionDef -> "${def.irFunction.name}() -> ${def.returnTypeClass.name}"
                        is Definition.DslDef -> "(dsl) ${def.irClass.name}"
                        is Definition.ExternalFunctionDef -> "(external) -> ${def.returnTypeClass.name}"
                    }
                    val defType = def.definitionType.name.lowercase()
                    val scopeClass = def.scopeClass  // Local vals for smart cast
                    val scopeArchetype = def.scopeArchetype
                    val extras = buildList {
                        if (def.bindings.isNotEmpty()) {
                            add("binds: ${def.bindings.joinToString(", ") { it.name.asString() }}")
                        }
                        if (scopeClass != null) {
                            add("scope: ${scopeClass.name}")
                        }
                        if (scopeArchetype != null) {
                            add(scopeArchetype.name.lowercase())
                        }
                        if (def.createdAtStart) {
                            add("createdAtStart")
                        }
                    }
                    val extraStr = if (extras.isNotEmpty()) " (${extras.joinToString(", ")})" else ""
                    KoinPluginLogger.user { "  $defType: $defName$extraStr" }
                }
            }

            // Compile-time safety: build full visibility set and validate.
            // Skip A2 for modules that are included by another local module — they'll
            // be validated as part of the parent's visibility set (or at A3).
            val isIncludedByOtherModule = moduleClass.irClass.fqNameWhenAvailable in includedModuleFqNames
            if (safetyValidator != null && definitions.isNotEmpty() && !isIncludedByOtherModule) {
                val visibilityResult = buildVisibleDefinitions(moduleClass, definitions, moduleDefinitions)
                if (visibilityResult.isComplete) {
                    val moduleFqName = moduleClass.irClass.fqNameWhenAvailable?.asString()
                    safetyValidator.validate(
                        moduleClass.irClass.name.asString(),
                        moduleFqName,
                        definitions,
                        visibilityResult.definitions
                    )
                } else {
                    KoinPluginLogger.debug { "  Skipping A2 validation for ${moduleClass.irClass.name}: dependency module definitions incomplete (hint functions unavailable)" }
                }
            }

            // Check if FIR generated a function for this module (even if no local definitions)
            // This happens when @Module has @ComponentScan - definitions come from scanned packages
            val hasFirGeneratedFunction = moduleFragment.files.any { file ->
                file.declarations.filterIsInstance<IrSimpleFunction>().any { func ->
                    func.name.asString() == "module" &&
                    func.extensionReceiverParameter?.type?.classFqName?.asString() ==
                        moduleClass.irClass.fqNameWhenAvailable?.asString() &&
                    func.body == null  // FIR-generated, needs body
                }
            }

            // Skip if no definitions, no includes, AND no FIR-generated function that needs a body
            if (definitions.isEmpty() && moduleClass.includedModules.isEmpty() && !hasFirGeneratedFunction) {
                continue
            }

            val containingFile = moduleClass.irClass.parent as? IrFile ?: continue

            // Check if this class is from a dependency (main sources visible in test compilation)
            // by looking if a module() function already exists in compiled dependencies
            val existingFunction = findExistingModuleFunctionInDependencies(moduleClass.irClass)
            if (existingFunction != null) {
                KoinPluginLogger.debug { "  Skipping ${moduleClass.irClass.name} - module() already exists in dependency" }
                continue
            }

            // First check if a function was already generated by FIR
            // Try direct file search first, then fallback to context search (for functions in synthetic files)
            val firFunction = findModuleFunction(moduleFragment, moduleClass.irClass)
                ?: findModuleFunctionViaContext(moduleClass.irClass)
            if (firFunction != null) {
                try {
                    val parentFile = firFunction.parent as? IrFile
                    val parentFileName = parentFile?.name ?: "unknown"
                    val containingFileName = containingFile.name
                    KoinPluginLogger.debug { "  Found FIR-generated function for ${moduleClass.irClass.name} in file: $parentFileName (containingFile=$containingFileName)" }

                    // If the function is in a shared __GENERATED__CALLABLES__.kt file,
                    // move it to the module class's file to avoid class name collisions
                    KoinPluginLogger.debug { "    -> Checking parentFileName='$parentFileName', condition=${parentFileName == "__GENERATED__CALLABLES__.kt" && parentFile != null}" }
                    if (parentFileName == "__GENERATED__CALLABLES__.kt" && parentFile != null) {
                        // Check if function is already in containingFile
                        KoinPluginLogger.debug { "    -> Checking if function exists in containingFile" }
                        val alreadyInFile = try {
                            containingFile.declarations.any {
                                it is IrSimpleFunction && it.name.asString() == "module" &&
                                it.extensionReceiverParameter?.type?.classFqName?.asString() == moduleClass.irClass.fqNameWhenAvailable?.asString()
                            }
                        } catch (e: Exception) {
                            KoinPluginLogger.debug { "    -> ERROR in alreadyInFile check: ${e.message}" }
                            false
                        }
                        KoinPluginLogger.debug { "    -> alreadyInFile=$alreadyInFile" }
                        if (alreadyInFile) {
                            KoinPluginLogger.debug { "    -> Function already exists in ${containingFile.name}, skipping move" }
                        } else {
                            KoinPluginLogger.debug { "    -> Moving function from synthetic file to ${containingFile.name}" }
                            // Remove from synthetic file
                            parentFile.declarations.remove(firFunction)
                            // Add to module class's file
                            containingFile.declarations.add(firFunction)
                            firFunction.parent = containingFile
                        }
                    } else {
                        KoinPluginLogger.debug { "    -> NOT moving: parentFileName='$parentFileName', parentFile isNull=${parentFile == null}" }
                    }

                    moduleFunctions[moduleClass] = firFunction

                    // FIR-generated functions are already in metadata, no need to re-register
                    // (Re-registering can cause duplicates in context.referenceFunctions)
                    KoinPluginLogger.debug { "    -> Using FIR-generated function for ${moduleClass.irClass.name}.module()" }
                } catch (e: Exception) {
                    KoinPluginLogger.debug { "  ERROR processing ${moduleClass.irClass.name}: ${e.message}" }
                    e.printStackTrace()
                }
            } else {
                // Create a new function (fallback for older code paths)
                KoinPluginLogger.debug { "  Creating function for ${moduleClass.irClass.name}" }
                val function = createModuleFunction(moduleClass, containingFile)
                if (function != null) {
                    // Add to file declarations for bytecode generation
                    containingFile.declarations.add(function)
                    function.parent = containingFile
                    moduleFunctions[moduleClass] = function

                    // Register for downstream visibility
                    context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
                    KoinPluginLogger.debug { "    -> Created and registered ${moduleClass.irClass.name}.module() in ${containingFile.name}" }
                }
            }
        }

        // Second pass: Fill all function bodies (now all functions exist for includes())
        for ((moduleClass, function) in moduleFunctions) {
            fillFunctionBody(function, moduleClass)
        }
    }

    /**
     * Generate module-scoped component scan hints at IR time.
     *
     * For each @Configuration module with @ComponentScan, generates componentscan_* and
     * componentscanfunc_* hint functions that export what the module's scan discovered.
     * These hints are made visible to downstream compilations via registerFunctionAsMetadataVisible.
     *
     * This replaces FIR-time generation which couldn't discover @Inject constructor classes
     * in subpackages (FIR predicates don't index constructor annotations, and PSI scanning
     * doesn't work with KtLightSourceElement from Build Tools API).
     *
     * Pattern based on Metro's HintGenerator: creates a synthetic FirFile + IrFileImpl per hint
     * and registers the function for metadata serialization.
     */
    private fun generateModuleScanHints(
        moduleFragment: IrModuleFragment,
        moduleDefinitions: Map<ModuleClass, List<Definition>>
    ) {
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

        // Only process @Configuration modules with @ComponentScan
        val configModulesWithScan = moduleClasses.filter { moduleClass ->
            moduleClass.hasComponentScan && hasConfigurationAnnotation(moduleClass.irClass)
        }

        if (configModulesWithScan.isEmpty()) return

        KoinPluginLogger.debug { "generateModuleScanHints: ${configModulesWithScan.size} @Configuration modules with @ComponentScan" }

        for (moduleClass in configModulesWithScan) {
            val definitions = moduleDefinitions[moduleClass] ?: continue
            if (definitions.isEmpty()) continue

            val moduleClassId = moduleClass.irClass.classIdOrFail
            val sanitizedModuleId = KoinModuleFirGenerator.sanitizeModuleIdForHint(moduleClassId)

            KoinPluginLogger.debug { "  Module ${moduleClassId}: generating ${definitions.size} scan hints" }

            for (definition in definitions) {
                val defTypeStr = definitionTypeToString(definition.definitionType)
                val targetClass = definition.returnTypeClass
                val targetClassId = targetClass.classId ?: continue

                // Use module class as fallback for FIR metadata when targetClass is external (e.g., HttpClient from ktor)
                val fallback = moduleClass.irClass

                when (definition) {
                    is Definition.ClassDef, is Definition.ExternalFunctionDef -> {
                        // Class definition hint: componentscan_<moduleId>_<defType>
                        val hintName = KoinModuleFirGenerator.moduleScanHintFunctionName(sanitizedModuleId, defTypeStr)
                        generateSingleHint(moduleFragment, hintsPackage, hintName, targetClass, targetClassId, fallback)
                        KoinPluginLogger.debug { "    + componentscan hint: ${targetClass.name} ($defTypeStr)" }
                    }
                    is Definition.TopLevelFunctionDef -> {
                        // Function definition hint: componentscanfunc_<moduleId>_<defType>
                        val hintName = KoinModuleFirGenerator.moduleScanFunctionHintFunctionName(sanitizedModuleId, defTypeStr)
                        generateSingleHint(moduleFragment, hintsPackage, hintName, targetClass, targetClassId, fallback)
                        KoinPluginLogger.debug { "    + componentscanfunc hint: ${targetClass.name} ($defTypeStr)" }
                    }
                    is Definition.DslDef -> continue // DSL definitions don't generate hints
                    is Definition.FunctionDef -> {
                        // Skip: module-internal function definitions (@Singleton fun provide...() inside @Module classes)
                        // are NOT component-scanned. They are resolved directly from the module class, not via hints.
                    }
                }
            }
        }
    }

    /**
     * Generate a single hint function in a synthetic IR file and register it for metadata visibility.
     *
     * Creates a synthetic FirFile + IrFileImpl with a deterministic path, adds a simple function
     * with the target class type as parameter, and calls registerFunctionAsMetadataVisible to
     * ensure downstream compilations can discover it.
     *
     * Based on Metro's HintGenerator pattern.
     */
    private fun generateSingleHint(
        moduleFragment: IrModuleFragment,
        hintsPackage: FqName,
        hintName: Name,
        targetClass: IrClass,
        targetClassId: ClassId,
        fallbackMetadataSource: IrClass? = null
    ) {
        // Build the IR function
        val function = context.irFactory.createSimpleFunction(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = hintName,
            visibility = DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            returnType = context.irBuiltIns.unitType,
            modality = Modality.FINAL,
            symbol = IrSimpleFunctionSymbolImpl(),
            isTailrec = false,
            isSuspend = false,
            isOperator = false,
            isInfix = false,
            isExternal = false,
            containerSource = null,
            isFakeOverride = false
        )

        // Add parameter with the target class type
        val valueParam = context.irFactory.createValueParameter(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("contributed"),
            type = targetClass.defaultType,
            isAssignable = false,
            symbol = IrValueParameterSymbolImpl(),
            index = 0,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false
        )
        valueParam.parent = function
        function.valueParameters = listOf(valueParam)

        // Empty body (stub — hint functions are never called)
        function.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, emptyList())

        // Mark as @Deprecated(HIDDEN) to prevent ObjC export crashes on Native targets
        function.addDeprecatedHiddenAnnotation(context)

        // Build deterministic file name from target class + hint name
        val fileName = buildHintFileName(targetClassId, hintName)

        // Create synthetic FirFile for metadata
        // Try targetClass first, then fallback (e.g., module class) for external return types
        val firModuleData = extractFirModuleData(targetClass)
            ?: fallbackMetadataSource?.let { extractFirModuleData(it) }

        if (firModuleData == null) {
            KoinPluginLogger.debug { "    WARN: No FIR module data for ${targetClass.name}, skipping hint" }
            return
        }

        val firFile = buildFile {
            moduleData = firModuleData
            origin = FirDeclarationOrigin.Synthetic.PluginFile
            packageDirective = buildPackageDirective { packageFqName = hintsPackage }
            name = fileName
        }

        // Create synthetic IrFile with a deterministic fake path
        // (same approach as Metro — kotlinc IC needs an absolute-looking path)
        // Use fallback class's file entry when targetClass is external (no real file path)
        val sourceFileEntry = try {
            val entry = targetClass.fileEntry
            if (entry.name.contains("/") || entry.name.contains("\\")) entry
            else fallbackMetadataSource?.fileEntry ?: entry
        } catch (_: NotImplementedError) {
            fallbackMetadataSource?.fileEntry ?: throw IllegalStateException("No file entry for hint $hintName")
        }
        val fakeNewPath = Path(sourceFileEntry.name).parent.resolve(fileName)
        val hintFile = IrFileImpl(
            fileEntry = NaiveSourceBasedFileEntryImpl(fakeNewPath.absolutePathString()),
            packageFragmentDescriptor = EmptyPackageFragmentDescriptor(
                moduleFragment.descriptor,
                hintsPackage
            ),
            module = moduleFragment
        ).also { it.metadata = FirMetadataSource.File(firFile) }

        moduleFragment.addFile(hintFile)
        hintFile.addChild(function)

        // Register for downstream visibility — this is the key API that makes hints visible
        // to downstream compilations via Kotlin metadata in the compiled artifact.
        context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
    }

    /** Extract FIR module data from an IR class's metadata. */
    private fun extractFirModuleData(irClass: IrClass): FirModuleData? {
        return when (val src = irClass.metadata) {
            is FirMetadataSource.Class -> src.fir.moduleData
            is FirMetadataSource.Function -> src.fir.moduleData
            is FirMetadataSource.File -> src.fir.moduleData
            else -> null
        }
    }

    /**
     * Build a deterministic file name for a hint function.
     * Uses the target class package + class name + hint name to avoid collisions.
     */
    private fun buildHintFileName(targetClassId: ClassId, hintName: Name): String {
        val parts = sequence {
            yieldAll(targetClassId.packageFqName.pathSegments().map { it.asString() })
            yield(targetClassId.shortClassName.asString())
            yield(hintName.asString())
        }
        val fileName = parts
            .map { segment -> segment.replaceFirstChar { it.uppercaseChar() } }
            .joinToString(separator = "")
            .replaceFirstChar { it.lowercaseChar() }
        return "$fileName.kt"
    }

    /**
     * Check if a class has the @Configuration annotation.
     */
    private fun hasConfigurationAnnotation(irClass: IrClass): Boolean {
        return irClass.annotations.any {
            it.type.classFqName?.asString() == KoinAnnotationFqNames.CONFIGURATION.asString()
        }
    }

    /**
     * Convert DefinitionType enum to the string constant used in hint function names.
     */
    private fun definitionTypeToString(type: DefinitionType): String {
        return when (type) {
            DefinitionType.SINGLE -> KoinModuleFirGenerator.DEF_TYPE_SINGLE
            DefinitionType.FACTORY -> KoinModuleFirGenerator.DEF_TYPE_FACTORY
            DefinitionType.SCOPED -> KoinModuleFirGenerator.DEF_TYPE_SCOPED
            DefinitionType.VIEW_MODEL -> KoinModuleFirGenerator.DEF_TYPE_VIEWMODEL
            DefinitionType.WORKER -> KoinModuleFirGenerator.DEF_TYPE_WORKER
        }
    }

    /**
     * Create the module extension function (without body).
     * The body is filled in a second pass.
     */
    private fun createModuleFunction(moduleClass: ModuleClass, containingFile: IrFile): IrSimpleFunction? {
        val moduleClassSymbol = koinModuleClassSymbol ?: return null
        val moduleType = moduleClassSymbol.owner.defaultType

        val function = context.irFactory.createSimpleFunction(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("module"),
            visibility = DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            returnType = moduleType,
            modality = Modality.FINAL,
            symbol = IrSimpleFunctionSymbolImpl(),
            isTailrec = false,
            isSuspend = false,
            isOperator = false,
            isInfix = false,
            isExternal = false,
            containerSource = null,
            isFakeOverride = false
        )
        function.parent = containingFile

        // Extension receiver parameter (e.g., MyModule)
        val receiverType = moduleClass.irClass.defaultType
        val extensionReceiverParam = context.irFactory.createValueParameter(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.special("<this>"),
            type = receiverType,
            isAssignable = false,
            symbol = IrValueParameterSymbolImpl(),
            index = -1,  // Extension receiver uses -1
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false
        )
        extensionReceiverParam.parent = function
        function.extensionReceiverParameter = extensionReceiverParam

        return function
    }

    /**
     * Find the module extension function for a given module class.
     * Searches all files in the module fragment for a function named "module"
     * with extension receiver matching the target class.
     */
    private fun findModuleFunction(moduleFragment: IrModuleFragment, moduleClass: IrClass): IrSimpleFunction? {
        val targetFqName = moduleClass.fqNameWhenAvailable?.asString()
        for (file in moduleFragment.files) {
            for (func in file.declarations.filterIsInstance<IrSimpleFunction>()) {
                if (func.name.asString() != "module") continue
                val receiverFqName = func.extensionReceiverParameter?.type?.classFqName?.asString()
                if (receiverFqName == targetFqName) {
                    return func
                }
            }
        }
        return null
    }

    /**
     * Check if a module() function already exists for this class in compiled dependencies.
     * This is used to avoid creating duplicate functions when test sources include main sources.
     */
    private fun findExistingModuleFunctionInDependencies(moduleClass: IrClass): IrSimpleFunction? {
        val packageName = moduleClass.fqNameWhenAvailable?.parent() ?: return null

        // Query existing module() functions in this package
        val candidates = context.referenceFunctions(
            CallableId(packageName, Name.identifier("module"))
        )

        // Look for a function with matching extension receiver that's NOT from current compilation
        // (i.e., its parent is IrExternalPackageFragmentImpl, not IrFile)
        for (candidate in candidates) {
            val func = candidate.owner
            val extensionReceiverType = func.extensionReceiverParameter?.type
            val receiverFqName = (extensionReceiverType?.classifierOrNull as? IrClassSymbol)?.owner?.fqNameWhenAvailable

            if (receiverFqName == moduleClass.fqNameWhenAvailable) {
                // Check if this function is from a compiled dependency (not current compilation)
                val parent = func.parent
                if (parent !is IrFile) {
                    // It's from IrExternalPackageFragmentImpl - a compiled dependency
                    return func
                }
            }
        }
        return null
    }

    private fun fillFunctionBody(function: IrSimpleFunction, moduleClass: ModuleClass) {
        val definitions = collectAllDefinitions(moduleClass)
        KoinPluginLogger.debug { "  Filling body for ${moduleClass.irClass.name}.module(): ${definitions.size} definitions, ${moduleClass.includedModules.size} includes" }

        // Must generate body for FIR-generated functions even if no definitions
        // Empty modules with @ComponentScan scan other modules at runtime
        // Skip ONLY if we created this function and there's nothing to generate
        val isFirGenerated = function.body == null
        if (definitions.isEmpty() && moduleClass.includedModules.isEmpty() && !isFirGenerated) {
            KoinPluginLogger.debug { "    -> Skipping (no definitions or includes, not FIR-generated)" }
            return
        }

        KoinPluginLogger.debug { "    -> Generating module body with ${definitions.size} definitions" }

        val moduleDslFunction = context.referenceFunctions(
            CallableId(KoinAnnotationFqNames.MODULE_DSL, Name.identifier("module"))
        ).firstOrNull { it.owner.valueParameters.any { p ->
            p.name.asString() == "moduleDeclaration"
        }}?.owner
        if (moduleDslFunction == null) {
            KoinPluginLogger.error(
                "Cannot generate ${moduleClass.irClass.name}.module(): " +
                "org.koin.dsl.module() not found on classpath. " +
                "Please add io.insert-koin:koin-core to your dependencies."
            )
            // Generate error("Stub!") body to prevent backend crash
            generateErrorStubBody(function)
            return
        }

        val builder = DeclarationIrBuilder(context, function.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)

        val moduleCall = buildModuleCall(moduleDslFunction, definitions, moduleClass, function, builder)
        if (moduleCall == null) {
            // Generate error("Stub!") body to prevent backend crash
            generateErrorStubBody(function)
            return
        }

        function.body = context.irFactory.createBlockBody(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            listOf(builder.irReturn(moduleCall))
        )
    }

    /**
     * Generate a stub body: error("Stub!") to prevent "Function has no body" backend crashes.
     * Used when the function body can't be generated (e.g., koin-core not on classpath).
     */
    private fun generateErrorStubBody(function: IrSimpleFunction) {
        val builder = DeclarationIrBuilder(context, function.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
        val errorFunction = context.referenceFunctions(
            CallableId(FqName("kotlin"), Name.identifier("error"))
        ).firstOrNull()
        if (errorFunction != null) {
            function.body = builder.irBlockBody {
                +irCall(errorFunction).apply {
                    putValueArgument(0, irString("Koin compiler plugin: missing koin-core dependency"))
                }
            }
        }
    }

    /**
     * Collect all definitions: class-based + function-based
     */
    private fun collectAllDefinitions(moduleClass: ModuleClass): List<Definition> {
        val definitions = mutableListOf<Definition>()

        // Class-based definitions from component scan
        val matchingClasses = findMatchingDefinitions(moduleClass)
        val moduleFile = moduleClass.irClass.fileOrNull
        definitions.addAll(matchingClasses.map { defClass ->
            // IC: module depends on each scanned definition class
            trackClassLookup(lookupTracker, moduleFile, defClass.irClass)
            linkDeclarationsForIC(expectActualTracker, moduleFile, defClass.irClass)
            Definition.ClassDef(
                defClass.irClass,
                defClass.definitionType,
                defClass.bindings,
                defClass.scopeClass,
                defClass.scopeArchetype,
                defClass.createdAtStart
            )
        })

        // Function-based definitions from @Module class
        moduleClass.definitionFunctions.forEach { defFunc ->
            definitions.add(Definition.FunctionDef(
                defFunc.irFunction,
                moduleClass.irClass,
                defFunc.definitionType,
                defFunc.returnTypeClass,
                emptyList(), // bindings — function definitions don't yet support explicit binds
                defFunc.scopeClass, // Scope class from @Scope annotation
                defFunc.scopeArchetype, // Scope archetype from @ViewModelScope, @ActivityScope, etc.
                defFunc.createdAtStart
            ))
        }

        // Top-level function definitions from component scan
        val matchingTopLevelFunctions = findMatchingTopLevelFunctions(moduleClass)
        definitions.addAll(matchingTopLevelFunctions.map { defFunc ->
            Definition.TopLevelFunctionDef(
                defFunc.irFunction,
                defFunc.definitionType,
                defFunc.returnTypeClass,
                defFunc.bindings,
                defFunc.scopeClass,
                defFunc.scopeArchetype,
                defFunc.createdAtStart
            )
        })

        // Cross-module top-level function definitions from hints
        // These are provider-only (requirements validated in source module)
        if (moduleClass.hasComponentScan) {
            val scanPackages = moduleClass.scanPackages.ifEmpty {
                listOf(moduleClass.irClass.packageFqName?.asString() ?: "")
            }
            val crossModuleFunctionDefs = discoverFunctionDefinitionsFromHints(scanPackages)
            if (crossModuleFunctionDefs.isNotEmpty()) {
                KoinPluginLogger.debug { "  Found ${crossModuleFunctionDefs.size} cross-module function definitions" }
                if (KoinPluginLogger.userLogsEnabled) {
                    val moduleName = moduleClass.irClass.name.asString()
                    KoinPluginLogger.user { "  $moduleName: Cross-module scan found ${crossModuleFunctionDefs.size} function definitions:" }
                    crossModuleFunctionDefs.forEach { def ->
                        KoinPluginLogger.user { "    @${def.definitionType.name} providing ${def.returnTypeClass.name}" }
                    }
                }
                definitions.addAll(crossModuleFunctionDefs)
            }
        }

        return definitions
    }

    private fun findMatchingDefinitions(moduleClass: ModuleClass): List<DefinitionClass> {
        // Only scan if @ComponentScan is present
        if (!moduleClass.hasComponentScan) {
            return emptyList()
        }

        // If @ComponentScan has no value, scan current package and subpackages
        // If @ComponentScan has values, scan only those packages and their subpackages
        val scanPackages = moduleClass.effectiveScanPackages()

        KoinPluginLogger.debug { "  Scanning packages: ${scanPackages.joinToString(", ")} (recursive)" }

        // Local definitions from current compilation unit
        val localDefinitions = definitionClasses.filter { definition ->
            matchesScanPackages(definition.packageFqName.asString(), scanPackages)
        }

        // Cross-module definitions from hints (definitions from other Gradle modules)
        val crossModuleDefinitions = discoverDefinitionsFromHints(scanPackages)

        KoinPluginLogger.debug { "  Found ${localDefinitions.size} local definitions, ${crossModuleDefinitions.size} cross-module definitions" }

        // Log cross-module discoveries at user level (guard to avoid precomputation when logging is disabled)
        if (KoinPluginLogger.userLogsEnabled && crossModuleDefinitions.isNotEmpty()) {
            val moduleName = moduleClass.irClass.name.asString()
            val uniqueDefs = crossModuleDefinitions.distinctBy { it.irClass.fqNameWhenAvailable }
            KoinPluginLogger.user { "  $moduleName: Cross-module scan found ${uniqueDefs.size} definitions:" }
            uniqueDefs.forEach { def ->
                KoinPluginLogger.user { "    @${def.definitionType.name} on class ${def.irClass.name}" }
            }
        }

        return localDefinitions + crossModuleDefinitions
    }

    /**
     * Find top-level functions with definition annotations that match the module's scan packages.
     */
    private fun findMatchingTopLevelFunctions(moduleClass: ModuleClass): List<DefinitionTopLevelFunction> {
        // Only scan if @ComponentScan is present
        if (!moduleClass.hasComponentScan) {
            return emptyList()
        }

        val scanPackages = moduleClass.effectiveScanPackages()

        val matchingFunctions = definitionTopLevelFunctions.filter { definition ->
            matchesScanPackages(definition.packageFqName.asString(), scanPackages)
        }

        if (KoinPluginLogger.userLogsEnabled && matchingFunctions.isNotEmpty()) {
            val moduleName = moduleClass.irClass.name.asString()
            KoinPluginLogger.user { "  $moduleName: Found ${matchingFunctions.size} top-level function definitions:" }
            matchingFunctions.forEach { def ->
                KoinPluginLogger.user { "    @${def.definitionType.name} on function ${def.irFunction.name}()" }
            }
        }

        return matchingFunctions
    }

    /**
     * Discover definition classes from hint functions in dependencies.
     * Used for cross-module @ComponentScan - discovers @KoinViewModel, @Singleton, etc. from other Gradle modules.
     */
    private fun discoverDefinitionsFromHints(scanPackages: List<String>): List<DefinitionClass> {
        val discovered = mutableListOf<DefinitionClass>()

        // Query each definition type
        for (defType in KoinModuleFirGenerator.ALL_DEFINITION_TYPES) {
            val functionName = KoinModuleFirGenerator.definitionHintFunctionName(defType)
            val hintFunctions = context.referenceFunctions(
                CallableId(KoinModuleFirGenerator.HINTS_PACKAGE, functionName)
            )

            KoinPluginLogger.debug { "  Querying hints: $functionName -> ${hintFunctions.count()} functions" }

            for (hintFuncSymbol in hintFunctions) {
                val hintFunc = hintFuncSymbol.owner
                // The first parameter type is the definition class
                val paramType = hintFunc.valueParameters.firstOrNull()?.type ?: continue
                val defClass = (paramType.classifierOrNull as? IrClassSymbol)?.owner ?: continue

                // Check if the class's package matches scan packages
                val defPackage = defClass.packageFqName?.asString() ?: continue
                if (!matchesScanPackages(defPackage, scanPackages)) continue

                // Skip if we already have this class in local definitions (avoid duplicates)
                if (definitionClasses.any { it.irClass.fqNameWhenAvailable == defClass.fqNameWhenAvailable }) {
                    KoinPluginLogger.debug { "    Skipping ${defClass.name} - already in local definitions" }
                    continue
                }

                // Convert hint type to DefinitionType
                val definitionType = parseDefinitionType(defType) ?: continue

                // Extract bindings and other metadata from the class annotations
                val bindings = detectBindings(defClass) + getExplicitBindings(defClass)
                val scopeClass = getScopeClass(defClass)
                val createdAtStart = getCreatedAtStart(defClass)

                KoinPluginLogger.debug { "    Discovered: ${defClass.name} ($defType) from package $defPackage" }

                discovered.add(DefinitionClass(
                    irClass = defClass,
                    definitionType = definitionType,
                    packageFqName = FqName(defPackage),
                    bindings = bindings.distinctBy { it.fqNameWhenAvailable },
                    scopeClass = scopeClass,
                    scopeArchetype = getScopeArchetype(defClass),
                    createdAtStart = createdAtStart
                ))
            }
        }

        return discovered
    }

    /**
     * Discover top-level function definitions from hint functions in dependencies.
     * Used for cross-module @ComponentScan - discovers @Singleton fun provide...() etc. from other Gradle modules.
     * Creates ExternalFunctionDef instances (provider-only, no requirements to validate).
     *
     * Note: Package filtering is based on the function's **return type** package, not the function's
     * own package. This is because hints encode the return type (what the function provides).
     * If the function returns a type from a different package than the function itself, the scan
     * package must match the return type's package.
     */
    private fun discoverFunctionDefinitionsFromHints(scanPackages: List<String>): List<Definition.ExternalFunctionDef> {
        val discovered = mutableListOf<Definition.ExternalFunctionDef>()

        for (defType in KoinModuleFirGenerator.ALL_DEFINITION_TYPES) {
            val functionName = KoinModuleFirGenerator.definitionFunctionHintFunctionName(defType)
            val hintFunctions = context.referenceFunctions(
                CallableId(KoinModuleFirGenerator.HINTS_PACKAGE, functionName)
            )

            KoinPluginLogger.debug { "  Querying function hints: $functionName -> ${hintFunctions.count()} functions" }

            for (hintFuncSymbol in hintFunctions) {
                val hintFunc = hintFuncSymbol.owner
                // The first parameter type is the return type of the original function (what it provides)
                val paramType = hintFunc.valueParameters.firstOrNull()?.type ?: continue
                val returnTypeClass = (paramType.classifierOrNull as? IrClassSymbol)?.owner ?: continue

                // Check if the class's package matches scan packages
                val defPackage = returnTypeClass.packageFqName?.asString() ?: continue
                if (!matchesScanPackages(defPackage, scanPackages)) continue

                // Skip if we already have this type in local top-level function definitions
                if (definitionTopLevelFunctions.any { it.returnTypeClass.fqNameWhenAvailable == returnTypeClass.fqNameWhenAvailable }) {
                    KoinPluginLogger.debug { "    Skipping ${returnTypeClass.name} - already in local function definitions" }
                    continue
                }

                val definitionType = parseDefinitionType(defType) ?: continue

                KoinPluginLogger.debug { "    Discovered function def: ${returnTypeClass.name} ($defType) from package $defPackage" }

                discovered.add(Definition.ExternalFunctionDef(
                    definitionType = definitionType,
                    returnTypeClass = returnTypeClass
                ))
            }
        }

        return discovered
    }

    /**
     * Collect definitions from a module in a dependency JAR.
     *
     * A @Module without @ComponentScan only declares function definitions — these are always
     * fully resolvable from the JAR. A @Module with @ComponentScan also gathers definitions
     * from scanned packages, which may not have hints and can't be fully discovered.
     *
     * @param moduleFqName Fully qualified name of the module class
     * @return Result with definitions and completeness flag
     */
    internal fun collectDefinitionsFromDependencyModule(
        moduleFqName: String,
        visited: MutableSet<String> = mutableSetOf()
    ): DependencyModuleResult {
        // Guard against circular module includes (A includes B, B includes A)
        if (!visited.add(moduleFqName)) {
            KoinPluginLogger.debug { "      Cycle detected: $moduleFqName already visited, skipping" }
            return DependencyModuleResult(emptyList(), isComplete = true)
        }

        val definitions = mutableListOf<Definition>()
        val moduleClassId = ClassId.topLevel(FqName(moduleFqName))
        val moduleClassSymbol = context.referenceClass(moduleClassId)

        if (moduleClassSymbol == null) {
            // Module class not on classpath — can't resolve. Try hints as best-effort.
            val modulePackage = FqName(moduleFqName).parent().asString()
            KoinPluginLogger.debug { "      Cannot resolve $moduleFqName, using package $modulePackage for hint discovery" }

            // Primary: module-scoped scan hints (componentscan_* / componentscanfunc_*)
            // These work even when the module class isn't resolvable because they're looked up by name.
            val scanDefs = discoverModuleScanDefinitions(moduleFqName)
            definitions.addAll(scanDefs)

            // Fallback: orphan hints (definition_* / definitionfunc_*) in the module's package
            val existingFqNames = definitions.mapNotNull { it.returnTypeClass.fqNameWhenAvailable }.toSet()
            val orphanDefs = discoverClassDefinitionsFromHints(listOf(modulePackage))
            val orphanFuncDefs = discoverFunctionDefinitionsFromHints(listOf(modulePackage))
            definitions.addAll(orphanDefs.filter { it.returnTypeClass.fqNameWhenAvailable !in existingFqNames })
            definitions.addAll(orphanFuncDefs.filter { it.returnTypeClass.fqNameWhenAvailable !in existingFqNames })

            return DependencyModuleResult(definitions, isComplete = definitions.isNotEmpty())
        }

        val moduleIrClass = moduleClassSymbol.owner
        KoinPluginLogger.debug { "      Resolved class $moduleFqName, declarations: ${moduleIrClass.declarations.size}" }

        // 1. Extract function definitions from the module class (e.g., @Singleton fun providesXxx(): Xxx)
        //    A @Module without @ComponentScan only has these — they're always fully resolvable.
        val moduleFunctions = collectDefinitionFunctions(moduleIrClass)
        KoinPluginLogger.debug { "      Module functions: ${moduleFunctions.size} (${moduleFunctions.joinToString { it.irFunction.name.asString() }})" }
        for (defFunc in moduleFunctions) {
            definitions.add(Definition.FunctionDef(
                defFunc.irFunction,
                moduleIrClass,
                defFunc.definitionType,
                defFunc.returnTypeClass,
                emptyList(), // bindings — function definitions don't yet support explicit binds
                defFunc.scopeClass,
                defFunc.scopeArchetype,
                defFunc.createdAtStart
            ))
        }

        // 2. Check if the module has @ComponentScan — if so, discover scanned definitions
        //    from module-scoped scan hints (componentscan_* / componentscanfunc_*).
        //    These hints export what the module's @ComponentScan found, making it fully visible.
        //    Also query orphan hints (definition_*) as fallback for transitive cross-module definitions.
        val hasComponentScan = moduleIrClass.annotations.any {
            it.type.classFqName?.asString() == "org.koin.core.annotation.ComponentScan"
        }
        var scanDefinitionsFound = false
        if (hasComponentScan) {
            // Primary: module-scoped scan hints (always complete when available)
            val scanDefs = discoverModuleScanDefinitions(moduleFqName)
            definitions.addAll(scanDefs)

            // Fallback: orphan hints for transitive cross-module definitions
            // (definitions from a third module that were scanned by this module)
            val scanPackages = getComponentScanPackages(moduleIrClass)
            val effectiveScanPackages = scanPackages.ifEmpty {
                listOf(moduleIrClass.packageFqName?.asString() ?: "")
            }
            val orphanDefs = discoverClassDefinitionsFromHints(effectiveScanPackages)
            val orphanFuncDefs = discoverFunctionDefinitionsFromHints(effectiveScanPackages)
            // Deduplicate — scan hints may overlap with orphan hints
            val existingFqNames = definitions.mapNotNull { it.returnTypeClass.fqNameWhenAvailable }.toSet()
            val newOrphanDefs = orphanDefs.filter { it.returnTypeClass.fqNameWhenAvailable !in existingFqNames }
            val newOrphanFuncDefs = orphanFuncDefs.filter { it.returnTypeClass.fqNameWhenAvailable !in existingFqNames }
            definitions.addAll(newOrphanDefs)
            definitions.addAll(newOrphanFuncDefs)

            scanDefinitionsFound = scanDefs.isNotEmpty() || newOrphanDefs.isNotEmpty() || newOrphanFuncDefs.isNotEmpty()
        }

        // 3. Follow @Module(includes = [...]) to collect definitions from included modules.
        //    Included modules (e.g., DatabaseKoinModule included by DaosKoinModule) may not be
        //    @Configuration, so they won't appear in the top-level module list. We must collect
        //    their definitions here to make them visible for A3 full-graph validation.
        val includedModules = getModuleIncludes(moduleIrClass)
        val knownFqNames = definitions.mapNotNull { it.returnTypeClass.fqNameWhenAvailable }.toMutableSet()
        for (included in includedModules) {
            val includedFqName = included.fqNameWhenAvailable?.asString() ?: continue
            // Check if already collected locally (avoid re-processing)
            val localModuleClass = collectedModuleClasses.find {
                it.irClass.fqNameWhenAvailable == included.fqNameWhenAvailable
            }
            val newDefs = if (localModuleClass != null) {
                // Included module is local — use collectAllDefinitions
                val includedDefs = collectAllDefinitions(localModuleClass)
                includedDefs.filter { it.returnTypeClass.fqNameWhenAvailable !in knownFqNames }
            } else {
                // Included module from JAR — recursively collect
                val includedResult = collectDefinitionsFromDependencyModule(includedFqName, visited)
                includedResult.definitions.filter { it.returnTypeClass.fqNameWhenAvailable !in knownFqNames }
            }
            definitions.addAll(newDefs)
            knownFqNames.addAll(newDefs.mapNotNull { it.returnTypeClass.fqNameWhenAvailable })
            val source = if (localModuleClass != null) "local" else "dependency"
            KoinPluginLogger.debug { "      Included ($source) $includedFqName: ${newDefs.size} new definitions" }
        }

        if (definitions.isNotEmpty()) {
            KoinPluginLogger.debug { "    -> Found ${definitions.size} definitions from $moduleFqName (hasComponentScan=$hasComponentScan)" }
        }

        // Module is complete if it has no @ComponentScan, or if scan definitions were found.
        // When hint functions are unavailable, discoverModuleScanDefinitions returns empty.
        // In that case, we can't fully resolve the module's scanned definitions and must
        // mark it incomplete to skip safety validation.
        val isComplete = !hasComponentScan || scanDefinitionsFound
        if (!isComplete) {
            KoinPluginLogger.debug { "    -> WARNING: $moduleFqName has @ComponentScan but no scan hints found (hint functions unavailable)" }
        }
        return DependencyModuleResult(definitions, isComplete = isComplete)
    }

    /**
     * Discover class definitions from hints and convert to Definition.ClassDef.
     */
    private fun discoverClassDefinitionsFromHints(scanPackages: List<String>): List<Definition.ClassDef> {
        return discoverDefinitionsFromHints(scanPackages).map { defClass ->
            Definition.ClassDef(
                defClass.irClass,
                defClass.definitionType,
                defClass.bindings,
                defClass.scopeClass,
                defClass.scopeArchetype,
                defClass.createdAtStart
            )
        }
    }

    /**
     * Discover definitions from module-scoped component scan hints.
     * These hints are generated for each @Configuration module with @ComponentScan,
     * exporting what the module's scan found. This makes @ComponentScan results
     * visible cross-module without requiring the definitions themselves to have orphan hints.
     *
     * @param moduleFqName Fully qualified name of the module class
     * @return List of definitions discovered via module-scan hints
     */
    private fun discoverModuleScanDefinitions(moduleFqName: String): List<Definition> {
        val moduleClassId = ClassId.topLevel(FqName(moduleFqName))
        val sanitizedId = KoinModuleFirGenerator.sanitizeModuleIdForHint(moduleClassId)
        val definitions = mutableListOf<Definition>()

        KoinPluginLogger.debug { "      Querying module-scan hints for $moduleFqName (id=$sanitizedId)" }

        for (defType in KoinModuleFirGenerator.ALL_DEFINITION_TYPES) {
            // Class definition hints: componentscan_<moduleId>_<defType>
            val className = KoinModuleFirGenerator.moduleScanHintFunctionName(sanitizedId, defType)
            val hintFunctions = context.referenceFunctions(
                CallableId(KoinModuleFirGenerator.HINTS_PACKAGE, className)
            )

            for (hintFuncSymbol in hintFunctions) {
                val hintFunc = hintFuncSymbol.owner
                val paramType = hintFunc.valueParameters.firstOrNull()?.type ?: continue
                val defClass = (paramType.classifierOrNull as? IrClassSymbol)?.owner ?: continue

                // Skip duplicates
                if (definitions.any { it.returnTypeClass.fqNameWhenAvailable == defClass.fqNameWhenAvailable }) continue

                val definitionType = parseDefinitionType(defType) ?: continue

                val bindings = detectBindings(defClass) + getExplicitBindings(defClass)
                val scopeClass = getScopeClass(defClass)
                val createdAtStart = getCreatedAtStart(defClass)

                KoinPluginLogger.debug { "        Found: ${defClass.name} ($defType) via module-scan hint" }

                definitions.add(Definition.ClassDef(
                    defClass,
                    definitionType,
                    bindings.distinctBy { it.fqNameWhenAvailable },
                    scopeClass,
                    getScopeArchetype(defClass),
                    createdAtStart
                ))
            }

            // Function definition hints: componentscanfunc_<moduleId>_<defType>
            val funcName = KoinModuleFirGenerator.moduleScanFunctionHintFunctionName(sanitizedId, defType)
            val funcHintFunctions = context.referenceFunctions(
                CallableId(KoinModuleFirGenerator.HINTS_PACKAGE, funcName)
            )

            for (hintFuncSymbol in funcHintFunctions) {
                val hintFunc = hintFuncSymbol.owner
                val paramType = hintFunc.valueParameters.firstOrNull()?.type ?: continue
                val returnTypeClass = (paramType.classifierOrNull as? IrClassSymbol)?.owner ?: continue

                if (definitions.any { it.returnTypeClass.fqNameWhenAvailable == returnTypeClass.fqNameWhenAvailable }) continue

                val definitionType = parseDefinitionType(defType) ?: continue

                KoinPluginLogger.debug { "        Found: ${returnTypeClass.name} ($defType) via module-scan function hint" }

                definitions.add(Definition.ExternalFunctionDef(
                    definitionType = definitionType,
                    returnTypeClass = returnTypeClass
                ))
            }
        }

        if (definitions.isNotEmpty()) {
            KoinPluginLogger.debug { "      -> ${definitions.size} definitions from module-scan hints for $moduleFqName" }
        }

        return definitions
    }

    /** Convert a hint definition type string to [DefinitionType]. */
    private fun parseDefinitionType(defType: String): DefinitionType? {
        return when (defType) {
            KoinModuleFirGenerator.DEF_TYPE_SINGLE -> DefinitionType.SINGLE
            KoinModuleFirGenerator.DEF_TYPE_FACTORY -> DefinitionType.FACTORY
            KoinModuleFirGenerator.DEF_TYPE_SCOPED -> DefinitionType.SCOPED
            KoinModuleFirGenerator.DEF_TYPE_VIEWMODEL -> DefinitionType.VIEW_MODEL
            KoinModuleFirGenerator.DEF_TYPE_WORKER -> DefinitionType.WORKER
            else -> null
        }
    }

    /** Check if a package matches any of the scan packages (exact or subpackage). */
    private fun matchesScanPackages(defPackage: String, scanPackages: List<String>): Boolean {
        return scanPackages.any { scanPkg ->
            defPackage == scanPkg || defPackage.startsWith("$scanPkg.")
        }
    }

    /** Resolve effective scan packages: explicit scan packages or default to module's own package. */
    private fun ModuleClass.effectiveScanPackages(): List<String> {
        return scanPackages.ifEmpty {
            listOf(irClass.packageFqName?.asString() ?: "")
        }
    }

    /**
     * Result of building visible definitions for a module.
     * @param definitions All definitions visible to the module
     * @param isComplete Whether all dependency modules' definitions could be fully resolved.
     *   False when a dependency module has @ComponentScan but no hint functions are available.
     */
    private data class VisibilityResult(
        val definitions: List<Definition>,
        val isComplete: Boolean
    )

    /**
     * Build the full set of definitions visible to a module for validation.
     * Includes: own definitions + included modules + @Configuration siblings (local + cross-module).
     */
    private fun buildVisibleDefinitions(
        moduleClass: ModuleClass,
        ownDefinitions: List<Definition>,
        allModuleDefinitions: Map<ModuleClass, List<Definition>>
    ): VisibilityResult {
        // Pre-build lookup map for O(1) sibling resolution
        val modulesByFqName = moduleClasses.associateBy { it.irClass.fqNameWhenAvailable?.asString() }
        var allComplete = true

        val definitions = buildList {
            addAll(ownDefinitions)

            // A1: Explicit includes
            for (included in moduleClass.includedModules) {
                val includedModule = modulesByFqName[included.fqNameWhenAvailable?.asString()]
                if (includedModule != null) {
                    // Local included module
                    addAll(allModuleDefinitions[includedModule] ?: emptyList())
                } else {
                    // Cross-module included module from dependency JAR
                    val includedFqName = included.fqNameWhenAvailable?.asString() ?: continue
                    val result = collectDefinitionsFromDependencyModule(includedFqName)
                    addAll(result.definitions)
                    if (!result.isComplete) allComplete = false
                }
            }

            // A2: @Configuration siblings — discover via hint functions
            val configLabels = extractConfigurationLabels(moduleClass.irClass)
            if (configLabels.isNotEmpty()) {
                val modFile = moduleClass.irClass.fileOrNull
                val siblingClasses = discoverConfigurationModulesFromHints(configLabels)
                for (siblingClass in siblingClasses) {
                    val siblingFqName = siblingClass.fqNameWhenAvailable?.asString() ?: continue
                    // Skip self
                    if (siblingFqName == moduleClass.irClass.fqNameWhenAvailable?.asString()) continue

                    // IC: module depends on each @Configuration sibling
                    trackClassLookup(lookupTracker, modFile, siblingClass)
                    linkDeclarationsForIC(expectActualTracker, modFile, siblingClass)

                    val sibling = modulesByFqName[siblingFqName]
                    if (sibling != null) {
                        // Local sibling
                        addAll(allModuleDefinitions[sibling] ?: emptyList())
                    } else {
                        // Cross-Gradle-module sibling — resolve from JAR + module scan hints
                        val result = collectDefinitionsFromDependencyModule(siblingFqName)
                        addAll(result.definitions)
                        if (!result.isComplete) allComplete = false
                    }
                }
            }
        }
        return VisibilityResult(definitions, allComplete)
    }

    /**
     * Discover @Configuration modules from hint functions (local + dependencies).
     * Queries configuration_<label> hint functions via context.referenceFunctions(),
     * which sees both local FIR-generated hints and dependency hints from klib/JAR metadata.
     */
    private fun discoverConfigurationModulesFromHints(labels: List<String>): List<IrClass> {
        val modules = mutableListOf<IrClass>()
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

        for (label in labels) {
            val callableId = CallableId(hintsPackage, KoinModuleFirGenerator.hintFunctionNameForLabel(label))
            val hintFunctions = context.referenceFunctions(callableId)

            for (hintFuncSymbol in hintFunctions) {
                val hintFunc = hintFuncSymbol.owner
                val paramType = hintFunc.valueParameters.firstOrNull()?.type
                val moduleClass = (paramType?.classifierOrNull as? IrClassSymbol)?.owner
                if (moduleClass != null && moduleClass !in modules) {
                    modules.add(moduleClass)
                }
            }
        }

        KoinPluginLogger.debug { "A2: Discovered ${modules.size} @Configuration siblings from hints for labels $labels" }
        return modules
    }

    private fun buildModuleCall(
        moduleDslFunction: IrSimpleFunction,
        definitions: List<Definition>,
        moduleClass: ModuleClass,
        parentFunction: IrFunction,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        val koinModuleIrClass = koinModuleClass ?: return null

        val lambdaFunction = context.irFactory.createSimpleFunction(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
            name = Name.special("<anonymous>"),
            visibility = DescriptorVisibilities.LOCAL,
            isInline = false,
            isExpect = false,
            returnType = context.irBuiltIns.unitType,
            modality = Modality.FINAL,
            symbol = IrSimpleFunctionSymbolImpl(),
            isTailrec = false,
            isSuspend = false,
            isOperator = false,
            isInfix = false,
            isExternal = false,
            containerSource = null,
            isFakeOverride = false
        )
        lambdaFunction.parent = parentFunction

        val moduleReceiverParam = context.irFactory.createValueParameter(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.special("<this>"),
            type = koinModuleIrClass.defaultType,
            isAssignable = false,
            symbol = IrValueParameterSymbolImpl(),
            index = -1,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false
        )
        moduleReceiverParam.parent = lambdaFunction
        lambdaFunction.extensionReceiverParameter = moduleReceiverParam

        val lambdaBuilder = DeclarationIrBuilder(context, lambdaFunction.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
        val statements = mutableListOf<IrStatement>()

        // Generate includes() calls for each included module
        for (includedModuleClass in moduleClass.includedModules) {
            val includesCall = buildIncludesCall(includedModuleClass, moduleReceiverParam, lambdaBuilder)
            if (includesCall != null) {
                statements.add(includesCall)
            }
        }

        // Separate definitions by scope type
        val rootDefinitions = definitions.filter { it.scopeClass == null && it.scopeArchetype == null }
        val scopedDefinitions = definitions.filter { it.scopeClass != null }
        val archetypeDefinitions = definitions.filter { it.scopeArchetype != null && it.scopeClass == null }
        val scopeGroups = scopedDefinitions.groupBy { it.scopeClass!! }
        val archetypeGroups = archetypeDefinitions.groupBy { it.scopeArchetype!! }

        // Generate root-scope definitions
        for (definition in rootDefinitions) {
            val definitionCall = when (definition) {
                is Definition.ClassDef -> definitionCallBuilder.buildClassDefinitionCall(definition, moduleReceiverParam, lambdaFunction, lambdaBuilder)
                is Definition.FunctionDef -> definitionCallBuilder.buildFunctionDefinitionCall(definition, moduleClass, moduleReceiverParam, lambdaFunction, lambdaBuilder, parentFunction)
                is Definition.TopLevelFunctionDef -> definitionCallBuilder.buildTopLevelFunctionDefinitionCall(definition, moduleReceiverParam, lambdaFunction, lambdaBuilder)
                is Definition.DslDef -> continue // DSL definitions are handled separately in Phase 2
                is Definition.ExternalFunctionDef -> continue // Provider-only, no code to generate
            }
            if (definitionCall != null) {
                statements.add(definitionCall)
            }
        }

        // Generate scope<ScopeClass> { ... } blocks for scoped definitions
        for ((scopeClass, scopeDefs) in scopeGroups) {
            val scopeBlock = buildScopeBlock(scopeClass, scopeDefs, moduleClass, moduleReceiverParam, lambdaFunction, lambdaBuilder, parentFunction)
            if (scopeBlock != null) {
                statements.add(scopeBlock)
            }
        }

        // Generate archetype scope blocks (viewModelScope { }, activityScope { }, etc.)
        for ((archetype, archetypeDefs) in archetypeGroups) {
            val archetypeBlock = buildArchetypeScopeBlock(archetype, archetypeDefs, moduleClass, moduleReceiverParam, lambdaFunction, lambdaBuilder, parentFunction)
            if (archetypeBlock != null) {
                statements.add(archetypeBlock)
            }
        }

        lambdaFunction.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, statements)

        val func1Class = function1Class ?: return null

        val lambdaType = func1Class.typeWith(koinModuleIrClass.defaultType, context.irBuiltIns.unitType)

        val lambdaExpr = IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = lambdaType,
            origin = IrStatementOrigin.LAMBDA,
            function = lambdaFunction
        )

        return builder.irCall(moduleDslFunction.symbol).apply {
            val moduleDeclarationIndex = moduleDslFunction.valueParameters.indexOfFirst {
                it.name.asString() == "moduleDeclaration"
            }
            if (moduleDeclarationIndex >= 0) {
                putValueArgument(moduleDeclarationIndex, lambdaExpr)
            }

            moduleDslFunction.valueParameters.forEachIndexed { index, param ->
                if (index != moduleDeclarationIndex && getValueArgument(index) == null) {
                    if (param.hasDefaultValue()) {
                        // Skip - will use default
                    } else if (param.type.isMarkedNullable()) {
                        putValueArgument(index, builder.irNull())
                    }
                }
            }
        }
    }

    /**
     * Build: scope<ScopeClass> { scoped(...) }
     *
     * Delegates to ScopeBlockBuilder for the scope block infrastructure.
     */
    private fun buildScopeBlock(
        scopeClass: IrClass,
        definitions: List<Definition>,
        moduleClass: ModuleClass,
        moduleReceiver: IrValueParameter,
        parentLambdaFunction: IrFunction,
        builder: DeclarationIrBuilder,
        parentFunction: IrFunction
    ): IrExpression? {
        return scopeBlockBuilder.buildScopeBlock(
            scopeClass = scopeClass,
            moduleReceiver = moduleReceiver,
            parentLambdaFunction = parentLambdaFunction,
            builder = builder
        ) { scopeDslReceiver, scopeLambdaFunction, scopeLambdaBuilder ->
            buildScopedDefinitions(definitions, moduleClass, scopeDslReceiver, scopeLambdaFunction, scopeLambdaBuilder, parentFunction)
        }
    }

    /**
     * Build archetype scope block: viewModelScope { }, activityScope { }, etc.
     *
     * Delegates to ScopeBlockBuilder for the scope block infrastructure.
     */
    private fun buildArchetypeScopeBlock(
        archetype: ScopeArchetype,
        definitions: List<Definition>,
        moduleClass: ModuleClass,
        moduleReceiver: IrValueParameter,
        parentLambdaFunction: IrFunction,
        builder: DeclarationIrBuilder,
        parentFunction: IrFunction
    ): IrExpression? {
        return scopeBlockBuilder.buildArchetypeScopeBlock(
            archetype = archetype,
            moduleReceiver = moduleReceiver,
            parentLambdaFunction = parentLambdaFunction,
            builder = builder
        ) { scopeDslReceiver, scopeLambdaFunction, scopeLambdaBuilder ->
            buildScopedDefinitions(definitions, moduleClass, scopeDslReceiver, scopeLambdaFunction, scopeLambdaBuilder, parentFunction)
        }
    }

    /**
     * Build scoped definition calls for a list of definitions.
     * Used inside scope blocks (both regular and archetype).
     */
    private fun buildScopedDefinitions(
        definitions: List<Definition>,
        moduleClass: ModuleClass,
        scopeDslReceiver: IrValueParameter,
        scopeLambdaFunction: IrFunction,
        scopeLambdaBuilder: DeclarationIrBuilder,
        parentFunction: IrFunction
    ): List<IrStatement> {
        val statements = mutableListOf<IrStatement>()
        for (definition in definitions) {
            val definitionCall = when (definition) {
                is Definition.ClassDef -> definitionCallBuilder.buildScopedClassDefinitionCall(definition, scopeDslReceiver, scopeLambdaFunction, scopeLambdaBuilder)
                is Definition.FunctionDef -> definitionCallBuilder.buildScopedFunctionDefinitionCall(definition, moduleClass, scopeDslReceiver, scopeLambdaFunction, scopeLambdaBuilder, parentFunction)
                is Definition.TopLevelFunctionDef -> definitionCallBuilder.buildScopedTopLevelFunctionDefinitionCall(definition, scopeDslReceiver, scopeLambdaFunction, scopeLambdaBuilder)
                is Definition.DslDef -> continue // DSL definitions are handled separately in Phase 2
                is Definition.ExternalFunctionDef -> continue // Provider-only, no code to generate
            }
            if (definitionCall != null) {
                statements.add(definitionCall)
            }
        }
        return statements
    }

    /**
     * Build: includes(IncludedModule().module())
     */
    private fun buildIncludesCall(
        includedModuleClass: IrClass,
        moduleReceiver: IrValueParameter,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        // Find the includes function: Module.includes(vararg Module)
        val includesFunction = context.referenceFunctions(
            CallableId(FqName("org.koin.plugin.module.dsl"), Name.identifier("includes"))
        ).firstOrNull { it.owner.extensionReceiverParameter?.type?.classFqName?.asString() == KoinAnnotationFqNames.KOIN_MODULE.asString() }?.owner
            ?: return null

        // Get the constructor of the included module class (or object instance)
        val instanceExpression = if (includedModuleClass.isObject) {
            builder.irGetObject(includedModuleClass.symbol)
        } else {
            val constructor = includedModuleClass.primaryConstructor ?: return null
            builder.irCallConstructor(constructor.symbol, emptyList())
        }

        // Find the extension function for .module()
        // First try the module fragment (same compilation), then try context.referenceFunctions (cross-compilation)
        val moduleFragment = currentModuleFragment ?: return null
        val moduleFunction = findModuleFunction(moduleFragment, includedModuleClass)
            ?: findModuleFunctionViaContext(includedModuleClass)
            ?: return null

        // Create: IncludedModule().module() (call the function with instance as receiver)
        val moduleGetCall = builder.irCall(moduleFunction.symbol).apply {
            extensionReceiver = instanceExpression
        }

        // Create: includes(IncludedModule().module())
        return builder.irCall(includesFunction.symbol).apply {
            extensionReceiver = builder.irGet(moduleReceiver)
            putValueArgument(0, moduleGetCall)
        }
    }

    /**
     * Find module() function via context.referenceFunctions for cross-compilation-unit lookup.
     */
    private fun findModuleFunctionViaContext(moduleClass: IrClass): IrSimpleFunction? {
        val packageName = moduleClass.fqNameWhenAvailable?.parent() ?: return null
        val functionCandidates = context.referenceFunctions(
            CallableId(packageName, Name.identifier("module"))
        )

        return functionCandidates.firstOrNull { func ->
            val extensionReceiverType = func.owner.extensionReceiverParameter?.type
            val receiverFqName = (extensionReceiverType?.classifierOrNull as? IrClassSymbol)?.owner?.fqNameWhenAvailable
            receiverFqName == moduleClass.fqNameWhenAvailable
        }?.owner
    }

}

/**
 * Detect interfaces/superclasses that should be auto-bound for a given class.
 * Shared utility used by both KoinAnnotationProcessor and KoinDSLTransformer.
 */
internal fun detectAutoBindings(declaration: IrClass): List<IrClass> {
    val bindings = mutableListOf<IrClass>()

    declaration.superTypes.forEach { superType ->
        val superClass = superType.classifierOrNull?.owner as? IrClass ?: return@forEach
        val superFqName = superClass.fqNameWhenAvailable?.asString() ?: return@forEach

        if (superFqName == "kotlin.Any") return@forEach

        if (superClass.isInterface || superClass.modality == Modality.ABSTRACT) {
            bindings.add(superClass)
        }
    }

    return bindings
}
