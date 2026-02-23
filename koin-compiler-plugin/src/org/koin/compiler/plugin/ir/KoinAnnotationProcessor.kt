package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
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
import org.koin.compiler.plugin.PropertyValueRegistry
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator

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
    private val context: IrPluginContext
) {

    // Qualifier extraction helper
    private val qualifierExtractor = QualifierExtractor(context)

    // Argument generator for lambda parameters
    private val argumentGenerator = KoinArgumentGenerator(context, qualifierExtractor)

    // Lambda builder helper
    private val lambdaBuilder = LambdaBuilder(context, qualifierExtractor, argumentGenerator)

    // Scope block builder helper
    private val scopeBlockBuilder = ScopeBlockBuilder(context)

    // Definition call builder helper
    private val definitionCallBuilder = DefinitionCallBuilder(context, qualifierExtractor, lambdaBuilder, argumentGenerator)

    // Annotation FQNames - use centralized registry
    private val moduleFqName = KoinAnnotationFqNames.MODULE
    private val componentScanFqName = KoinAnnotationFqNames.COMPONENT_SCAN
    private val singletonFqName = KoinAnnotationFqNames.SINGLETON
    private val singleFqName = KoinAnnotationFqNames.SINGLE
    private val factoryFqName = KoinAnnotationFqNames.FACTORY
    private val scopedFqName = KoinAnnotationFqNames.SCOPED
    private val scopeAnnotationFqName = KoinAnnotationFqNames.SCOPE
    private val koinViewModelFqName = KoinAnnotationFqNames.KOIN_VIEW_MODEL
    private val koinWorkerFqName = KoinAnnotationFqNames.KOIN_WORKER
    private val namedAnnotationFqName = KoinAnnotationFqNames.NAMED
    private val qualifierAnnotationFqName = KoinAnnotationFqNames.QUALIFIER
    private val injectedParamAnnotationFqName = KoinAnnotationFqNames.INJECTED_PARAM
    private val propertyAnnotationFqName = KoinAnnotationFqNames.PROPERTY
    private val propertyValueAnnotationFqName = KoinAnnotationFqNames.PROPERTY_VALUE

    // Scope archetype annotations
    private val viewModelScopeFqName = KoinAnnotationFqNames.VIEW_MODEL_SCOPE
    private val activityScopeFqName = KoinAnnotationFqNames.ACTIVITY_SCOPE
    private val activityRetainedScopeFqName = KoinAnnotationFqNames.ACTIVITY_RETAINED_SCOPE
    private val fragmentScopeFqName = KoinAnnotationFqNames.FRAGMENT_SCOPE

    // JSR-330 (jakarta.inject) annotations
    private val jakartaSingletonFqName = KoinAnnotationFqNames.JAKARTA_SINGLETON
    private val jakartaNamedFqName = KoinAnnotationFqNames.JAKARTA_NAMED
    private val jakartaInjectFqName = KoinAnnotationFqNames.JAKARTA_INJECT
    private val jakartaQualifierFqName = KoinAnnotationFqNames.JAKARTA_QUALIFIER

    // JSR-330 (javax.inject) annotations - legacy package still used by many projects
    private val javaxSingletonFqName = KoinAnnotationFqNames.JAVAX_SINGLETON
    private val javaxNamedFqName = KoinAnnotationFqNames.JAVAX_NAMED
    private val javaxInjectFqName = KoinAnnotationFqNames.JAVAX_INJECT
    private val javaxQualifierFqName = KoinAnnotationFqNames.JAVAX_QUALIFIER

    // Koin DSL FQNames
    private val koinModuleFqName = KoinAnnotationFqNames.KOIN_MODULE
    private val moduleDslFqName = KoinAnnotationFqNames.MODULE_DSL
    private val scopeFqName = KoinAnnotationFqNames.SCOPE_CLASS
    private val parametersHolderFqName = KoinAnnotationFqNames.PARAMETERS_HOLDER

    // Cached class lookups (avoid repeated context.referenceClass calls)
    private val kClassClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KCLASS))?.owner }
    private val function1Class by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.FUNCTION1))?.owner }
    private val function2Class by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.FUNCTION2))?.owner }
    private val scopeDslClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.SCOPE_DSL))?.owner }
    private val koinModuleClass by lazy { context.referenceClass(ClassId.topLevel(koinModuleFqName))?.owner }
    private val scopeClassCached by lazy { context.referenceClass(ClassId.topLevel(scopeFqName))?.owner }
    private val parametersHolderClass by lazy { context.referenceClass(ClassId.topLevel(parametersHolderFqName))?.owner }
    private val lazyModeClass by lazy { context.referenceClass(ClassId.topLevel(FqName("kotlin.LazyThreadSafetyMode"))) }
    private val koinModuleClassSymbol by lazy { context.referenceClass(ClassId.topLevel(koinModuleFqName)) }

    // Collected data (types defined in AnnotationModels.kt)
    private val moduleClasses = mutableListOf<ModuleClass>()
    private val definitionClasses = mutableListOf<DefinitionClass>()
    private val definitionTopLevelFunctions = mutableListOf<DefinitionTopLevelFunction>()

    /**
     * Phase 1: Collect all annotated classes, functions, and property values
     */
    fun collectAnnotations(moduleFragment: IrModuleFragment) {
        // Clear the property value registry for fresh compilation
        PropertyValueRegistry.clear()

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
            annotation.type.classFqName?.asString() == propertyValueAnnotationFqName.asString()
        }

        // Also check backing field annotations (annotations might be stored there)
        if (propertyValueAnnotation == null) {
            propertyValueAnnotation = declaration.backingField?.annotations?.firstOrNull { annotation ->
                annotation.type.classFqName?.asString() == propertyValueAnnotationFqName.asString()
            }
        }

        // Also check getter annotations
        if (propertyValueAnnotation == null) {
            propertyValueAnnotation = declaration.getter?.annotations?.firstOrNull { annotation ->
                annotation.type.classFqName?.asString() == propertyValueAnnotationFqName.asString()
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
        val hasModule = declaration.hasAnnotation(moduleFqName)
        val hasComponentScan = declaration.hasAnnotation(componentScanFqName)

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

            // Log definition annotation discovery (guard to avoid precomputation when logging is disabled)
            if (KoinPluginLogger.userLogsEnabled) {
                val annotationName = getDefinitionAnnotationName(declaration) ?: finalDefinitionType.name
                KoinPluginLogger.user { "@$annotationName on class ${declaration.name}" }
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
                // Log qualifier on class
                val qualifier = qualifierExtractor.extractFromDeclaration(declaration)
                when (qualifier) {
                    is QualifierValue.StringQualifier -> KoinPluginLogger.user { "  @Named(\"${qualifier.name}\")" }
                    is QualifierValue.TypeQualifier -> KoinPluginLogger.user { "  @Qualifier(${qualifier.irClass.name}::class)" }
                    null -> {}
                }
            }
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

        // Log definition annotation discovery
        if (KoinPluginLogger.userLogsEnabled) {
            val annotationName = getDefinitionAnnotationName(declaration) ?: definitionType.name
            KoinPluginLogger.user { "@$annotationName on function ${declaration.name}() -> ${returnTypeClass.name}" }
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
    }

    /**
     * Get the actual annotation name used on the declaration
     */
    private fun getDefinitionAnnotationName(declaration: IrDeclaration): String? {
        return when {
            declaration.hasAnnotation(singletonFqName) -> "Singleton"
            declaration.hasAnnotation(singleFqName) -> "Single"
            declaration.hasAnnotation(factoryFqName) -> "Factory"
            declaration.hasAnnotation(scopedFqName) -> "Scoped"
            declaration.hasAnnotation(koinViewModelFqName) -> "KoinViewModel"
            declaration.hasAnnotation(koinWorkerFqName) -> "KoinWorker"
            // Scope archetype annotations
            declaration.hasAnnotation(viewModelScopeFqName) -> "ViewModelScope"
            declaration.hasAnnotation(activityScopeFqName) -> "ActivityScope"
            declaration.hasAnnotation(activityRetainedScopeFqName) -> "ActivityRetainedScope"
            declaration.hasAnnotation(fragmentScopeFqName) -> "FragmentScope"
            declaration.hasAnnotation(jakartaSingletonFqName) -> "jakarta.inject.Singleton"
            declaration.hasAnnotation(jakartaInjectFqName) -> "jakarta.inject.Inject"
            declaration.hasAnnotation(javaxSingletonFqName) -> "javax.inject.Singleton"
            declaration.hasAnnotation(javaxInjectFqName) -> "javax.inject.Inject"
            // JSR-330: @Inject on constructor
            declaration is IrClass && hasInjectConstructor(declaration) -> "Inject constructor"
            else -> null
        }
    }

    /**
     * Get the scope class from @Scope(MyScope::class) annotation.
     * Works on both classes and functions.
     */
    private fun getScopeClass(declaration: IrDeclaration): IrClass? {
        val scopeAnnotation = declaration.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == scopeAnnotationFqName.asString()
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
            declaration.hasAnnotation(singletonFqName) -> DefinitionType.SINGLE
            declaration.hasAnnotation(singleFqName) -> DefinitionType.SINGLE
            declaration.hasAnnotation(factoryFqName) -> DefinitionType.FACTORY
            declaration.hasAnnotation(scopedFqName) -> DefinitionType.SCOPED
            declaration.hasAnnotation(koinViewModelFqName) -> DefinitionType.VIEW_MODEL
            declaration.hasAnnotation(koinWorkerFqName) -> DefinitionType.WORKER
            // Scope archetype annotations imply SCOPED definition
            declaration.hasAnnotation(viewModelScopeFqName) -> DefinitionType.SCOPED
            declaration.hasAnnotation(activityScopeFqName) -> DefinitionType.SCOPED
            declaration.hasAnnotation(activityRetainedScopeFqName) -> DefinitionType.SCOPED
            declaration.hasAnnotation(fragmentScopeFqName) -> DefinitionType.SCOPED
            // JSR-330 annotations (jakarta.inject)
            declaration.hasAnnotation(jakartaSingletonFqName) -> DefinitionType.SINGLE
            declaration.hasAnnotation(jakartaInjectFqName) -> DefinitionType.FACTORY // @Inject on class generates factory
            // JSR-330 annotations (javax.inject) - legacy package
            declaration.hasAnnotation(javaxSingletonFqName) -> DefinitionType.SINGLE
            declaration.hasAnnotation(javaxInjectFqName) -> DefinitionType.FACTORY // @Inject on class generates factory
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
                    fqName == jakartaInjectFqName.asString() || fqName == javaxInjectFqName.asString()
                }
            }
    }

    /**
     * Get scope archetype from annotations like @ViewModelScope, @ActivityScope, etc.
     * Works on both classes and functions.
     */
    private fun getScopeArchetype(declaration: IrDeclaration): ScopeArchetype? {
        return when {
            declaration.hasAnnotation(viewModelScopeFqName) -> ScopeArchetype.VIEW_MODEL_SCOPE
            declaration.hasAnnotation(activityScopeFqName) -> ScopeArchetype.ACTIVITY_SCOPE
            declaration.hasAnnotation(activityRetainedScopeFqName) -> ScopeArchetype.ACTIVITY_RETAINED_SCOPE
            declaration.hasAnnotation(fragmentScopeFqName) -> ScopeArchetype.FRAGMENT_SCOPE
            else -> null
        }
    }

    /**
     * Get createdAtStart parameter from @Single or @Singleton annotation
     */
    private fun getCreatedAtStart(declaration: IrDeclaration): Boolean {
        val annotation = declaration.annotations.firstOrNull { annotation ->
            val fqName = annotation.type.classFqName?.asString()
            fqName == singletonFqName.asString() || fqName == singleFqName.asString()
        } ?: return false

        // createdAtStart is usually the second parameter (index 1) after binds
        val createdAtStartArg = annotation.getValueArgument(1)
            ?: annotation.getValueArgument(Name.identifier("createdAtStart"))

        return when (createdAtStartArg) {
            is IrConst -> createdAtStartArg.value as? Boolean ?: false
            else -> false
        }
    }

    /**
     * Get explicit bindings from @Single(binds = [...]) or @Factory(binds = [...])
     */
    private fun getExplicitBindings(declaration: IrDeclaration): List<IrClass> {
        val definitionAnnotations = listOf(singletonFqName, singleFqName, factoryFqName, scopedFqName, koinViewModelFqName, koinWorkerFqName)

        val annotation = declaration.annotations.firstOrNull { annotation ->
            definitionAnnotations.any { it.asString() == annotation.type.classFqName?.asString() }
        } ?: return emptyList()

        // binds is usually the first parameter (index 0)
        val bindsArg = annotation.getValueArgument(0)
            ?: annotation.getValueArgument(Name.identifier("binds"))

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
                val scopeClass = getScopeClassFromDeclaration(function)
                val scopeArchetype = getScopeArchetype(function)
                val createdAtStart = getCreatedAtStart(function)
                DefinitionFunction(function, defType, returnTypeClass, scopeClass, scopeArchetype, createdAtStart)
            }
    }

    /**
     * Get scope class from @Scope annotation on any declaration (class or function)
     */
    private fun getScopeClassFromDeclaration(declaration: IrDeclarationWithName): IrClass? {
        val scopeAnnotation = declaration.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == scopeAnnotationFqName.asString()
        } ?: return null

        // @Scope(value = ScopeClass::class)
        val valueArg = scopeAnnotation.getValueArgument(0)
        if (valueArg is IrClassReferenceImpl) {
            return valueArg.classType.classifierOrNull?.owner as? IrClass
        }

        return null
    }

    /**
     * Detect interfaces/superclasses that should be auto-bound
     */
    private fun detectBindings(declaration: IrClass): List<IrClass> {
        val bindings = mutableListOf<IrClass>()

        // Get all supertypes except Any
        declaration.superTypes.forEach { superType ->
            val superClass = superType.classifierOrNull?.owner as? IrClass ?: return@forEach
            val superFqName = superClass.fqNameWhenAvailable?.asString() ?: return@forEach

            // Skip kotlin.Any and other common types
            if (superFqName == "kotlin.Any") return@forEach

            // Include interfaces and abstract classes
            if (superClass.isInterface || superClass.modality == Modality.ABSTRACT) {
                bindings.add(superClass)
            }
        }

        return bindings
    }

    private fun getComponentScanPackages(declaration: IrClass): List<String> {
        val annotation = declaration.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == componentScanFqName.asString()
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
            annotation.type.classFqName?.asString() == moduleFqName.asString()
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

        // Map to track functions for each module class (FIR-generated or newly created)
        val moduleFunctions = mutableMapOf<ModuleClass, IrSimpleFunction>()

        // First pass: Find FIR-generated functions or create new ones
        for (moduleClass in moduleClasses) {
            val definitions = collectAllDefinitions(moduleClass)

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
            CallableId(moduleDslFqName, Name.identifier("module"))
        ).firstOrNull { it.owner.valueParameters.any { p ->
            p.name.asString() == "moduleDeclaration"
        }}?.owner ?: return

        val builder = DeclarationIrBuilder(context, function.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)

        val moduleCall = buildModuleCall(moduleDslFunction, definitions, moduleClass, function, builder)
            ?: return

        function.body = context.irFactory.createBlockBody(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            listOf(builder.irReturn(moduleCall))
        )
    }

    /**
     * Collect all definitions: class-based + function-based
     */
    private fun collectAllDefinitions(moduleClass: ModuleClass): List<Definition> {
        val definitions = mutableListOf<Definition>()

        // Class-based definitions from component scan
        val matchingClasses = findMatchingDefinitions(moduleClass)
        definitions.addAll(matchingClasses.map { defClass ->
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
                emptyList(), // bindings - TODO: extract from function annotation
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

        return definitions
    }

    private fun findMatchingDefinitions(moduleClass: ModuleClass): List<DefinitionClass> {
        // Only scan if @ComponentScan is present
        if (!moduleClass.hasComponentScan) {
            return emptyList()
        }

        // If @ComponentScan has no value, scan current package and subpackages
        // If @ComponentScan has values, scan only those packages and their subpackages
        val scanPackages = moduleClass.scanPackages.ifEmpty {
            listOf(moduleClass.irClass.packageFqName?.asString() ?: "")
        }

        KoinPluginLogger.debug { "  Scanning packages: ${scanPackages.joinToString(", ")} (recursive)" }

        // Local definitions from current compilation unit
        val localDefinitions = definitionClasses.filter { definition ->
            val defPackage = definition.packageFqName.asString()
            // Match package or any subpackage (recursive: io.koin matches io.koin.feature1, io.koin.feature1.sub, etc.)
            scanPackages.any { scanPkg ->
                defPackage == scanPkg || defPackage.startsWith("$scanPkg.")
            }
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

        val scanPackages = moduleClass.scanPackages.ifEmpty {
            listOf(moduleClass.irClass.packageFqName?.asString() ?: "")
        }

        val matchingFunctions = definitionTopLevelFunctions.filter { definition ->
            val defPackage = definition.packageFqName.asString()
            scanPackages.any { scanPkg ->
                defPackage == scanPkg || defPackage.startsWith("$scanPkg.")
            }
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

            KoinPluginLogger.debug { "  Querying hints: $functionName -> ${hintFunctions.toList().size} functions" }

            for (hintFuncSymbol in hintFunctions) {
                val hintFunc = hintFuncSymbol.owner
                // The first parameter type is the definition class
                val paramType = hintFunc.valueParameters.firstOrNull()?.type ?: continue
                val defClass = (paramType.classifierOrNull as? IrClassSymbol)?.owner ?: continue

                // Check if the class's package matches scan packages
                val defPackage = defClass.packageFqName?.asString() ?: continue
                val matchesScanPackage = scanPackages.any { scanPkg ->
                    defPackage == scanPkg || defPackage.startsWith("$scanPkg.")
                }

                if (!matchesScanPackage) continue

                // Skip if we already have this class in local definitions (avoid duplicates)
                if (definitionClasses.any { it.irClass.fqNameWhenAvailable == defClass.fqNameWhenAvailable }) {
                    KoinPluginLogger.debug { "    Skipping ${defClass.name} - already in local definitions" }
                    continue
                }

                // Convert hint type to DefinitionType
                val definitionType = when (defType) {
                    KoinModuleFirGenerator.DEF_TYPE_SINGLE -> DefinitionType.SINGLE
                    KoinModuleFirGenerator.DEF_TYPE_FACTORY -> DefinitionType.FACTORY
                    KoinModuleFirGenerator.DEF_TYPE_SCOPED -> DefinitionType.SCOPED
                    KoinModuleFirGenerator.DEF_TYPE_VIEWMODEL -> DefinitionType.VIEW_MODEL
                    KoinModuleFirGenerator.DEF_TYPE_WORKER -> DefinitionType.WORKER
                    else -> continue
                }

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
                    scopeArchetype = getScopeArchetypeFromClass(defClass),
                    createdAtStart = createdAtStart
                ))
            }
        }

        return discovered
    }

    /**
     * Get scope archetype from class annotations (for cross-module discovery).
     */
    private fun getScopeArchetypeFromClass(irClass: IrClass): ScopeArchetype? {
        return when {
            irClass.hasAnnotation(viewModelScopeFqName) -> ScopeArchetype.VIEW_MODEL_SCOPE
            irClass.hasAnnotation(activityScopeFqName) -> ScopeArchetype.ACTIVITY_SCOPE
            irClass.hasAnnotation(activityRetainedScopeFqName) -> ScopeArchetype.ACTIVITY_RETAINED_SCOPE
            irClass.hasAnnotation(fragmentScopeFqName) -> ScopeArchetype.FRAGMENT_SCOPE
            else -> null
        }
    }

    private fun buildModuleCall(
        moduleDslFunction: IrSimpleFunction,
        definitions: List<Definition>,
        moduleClass: ModuleClass,
        parentFunction: IrFunction,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        val moduleClass2 = koinModuleClass ?: return null

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
            type = moduleClass2.defaultType,
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

        val lambdaType = func1Class.typeWith(moduleClass2.defaultType, context.irBuiltIns.unitType)

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
        ).firstOrNull { it.owner.extensionReceiverParameter?.type?.classFqName?.asString() == koinModuleFqName.asString() }?.owner
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
