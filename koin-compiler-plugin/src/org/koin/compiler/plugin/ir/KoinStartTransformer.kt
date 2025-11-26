package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.koin.compiler.plugin.KoinConfigurationRegistry
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Transforms calls to startKoin<T>(), koinApplication<T>(), koinConfiguration<T>(), and
 * KoinApplication.withConfiguration<T>() to inject modules.
 *
 * Input:
 * ```kotlin
 * @KoinApplication(modules = [MyModule::class])
 * object MyApp
 *
 * startKoin<MyApp> {
 *     printLogger()
 * }
 * // or
 * koinApplication<MyApp> { }
 * // or
 * koinConfiguration<MyApp>()
 * // or
 * koinApplication { }.withConfiguration<MyApp>()
 * ```
 *
 * Output:
 * ```kotlin
 * startKoinWith(listOf(MyModule().module())) {
 *     printLogger()
 * }
 * // or
 * koinApplicationWith(listOf(MyModule().module())) { }
 * // or
 * koinConfigurationWith(listOf(MyModule().module()))
 * // or
 * koinApplication { }.withConfigurationWith(listOf(MyModule().module()))
 * ```
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class KoinStartTransformer(
    private val context: IrPluginContext,
    private val moduleFragment: IrModuleFragment
) : IrElementTransformerVoid() {

    // Koin types
    private val koinModuleClassId = ClassId.topLevel(FqName("org.koin.core.module.Module"))

    // Annotation FQNames
    private val moduleFqName = FqName("org.koin.core.annotation.Module")
    private val configurationFqName = FqName("org.koin.core.annotation.Configuration")

    // Hint package for cross-module discovery (label-specific function names)
    private val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        val calleeFqName = callee.fqNameWhenAvailable

        // Check if this is our generic stub functions: startKoin<T>(), koinApplication<T>(), koinConfiguration<T>(),
        // or KoinApplication.withConfiguration<T>()
        // These get transformed to startKoinWith(modules, lambda), koinApplicationWith(modules, lambda),
        // koinConfigurationWith(modules), and withConfigurationWith(modules, lambda)
        val fqNameStr = calleeFqName?.asString()
        val isStartKoin = fqNameStr == "org.koin.plugin.module.dsl.startKoin"
        val isKoinApplication = fqNameStr == "org.koin.plugin.module.dsl.koinApplication"
        val isKoinConfiguration = fqNameStr == "org.koin.plugin.module.dsl.koinConfiguration"
        val isWithConfiguration = fqNameStr == "org.koin.plugin.module.dsl.withConfiguration" &&
            callee.extensionReceiverParameter?.type?.classFqName?.asString() == "org.koin.core.KoinApplication"

        if (!isStartKoin && !isKoinApplication && !isKoinConfiguration && !isWithConfiguration) {
            return super.visitCall(expression)
        }

        // Verify this is the generic version (has type parameter)
        // The implementation functions (startKoinWith, koinApplicationWith) have no type parameters
        if (callee.typeParameters.isEmpty()) {
            return super.visitCall(expression)
        }

        // Get the type argument T from startKoin<T>
        val typeArg = expression.getTypeArgument(0) ?: return super.visitCall(expression)
        val appClass = (typeArg.classifierOrNull as? IrClassSymbol)?.owner
            ?: return super.visitCall(expression)

        // Get modules from @KoinApplication(modules = [...]) annotation
        val moduleClasses = extractModulesFromKoinApplicationAnnotation(appClass)

        // Log interception (guard to avoid precomputation when logging is disabled)
        if (KoinPluginLogger.userLogsEnabled) {
            val appClassName = appClass.fqNameWhenAvailable?.asString() ?: "Unknown"
            val functionDisplayName = when {
                isStartKoin -> "startKoin<$appClassName>()"
                isKoinApplication -> "koinApplication<$appClassName>()"
                isKoinConfiguration -> "koinConfiguration<$appClassName>()"
                else -> "KoinApplication.withConfiguration<$appClassName>()"
            }
            KoinPluginLogger.user { "Intercepting $functionDisplayName" }
            if (moduleClasses.isNotEmpty()) {
                val moduleNames = moduleClasses.mapNotNull { it.fqNameWhenAvailable?.asString() }.joinToString(", ")
                KoinPluginLogger.user { "  -> Injecting modules: $moduleNames" }
            } else {
                KoinPluginLogger.user { "  -> No modules to inject" }
            }
        }

        // Get the lambda argument (first argument in the generic version, may be null if default)
        val lambdaArg = expression.getValueArgument(0)

        // Find the implementation function:
        // - startKoinWith(modules, lambda) for startKoin<T>()
        // - koinApplicationWith(modules, lambda) for koinApplication<T>()
        // - koinConfigurationWith(modules, lambda) for koinConfiguration<T>()
        // - withConfigurationWith(modules, lambda) for KoinApplication.withConfiguration<T>()
        val implFunctionName = when {
            isStartKoin -> "startKoinWith"
            isKoinApplication -> "koinApplicationWith"
            isKoinConfiguration -> "koinConfigurationWith"
            else -> "withConfigurationWith"
        }
        val implFunction = context.referenceFunctions(
            CallableId(FqName("org.koin.plugin.module.dsl"), Name.identifier(implFunctionName))
        ).firstOrNull { func ->
            func.owner.typeParameters.isEmpty() &&
            func.owner.valueParameters.size == 2
        }?.owner ?: return super.visitCall(expression)

        val koinModuleClass = context.referenceClass(koinModuleClassId)?.owner
            ?: return super.visitCall(expression)

        val builder = DeclarationIrBuilder(context, expression.symbol, expression.startOffset, expression.endOffset)

        // Build module expressions: listOf(Module1().module(), Module2().module(), ...)
        val moduleExpressions = moduleClasses.mapNotNull { moduleClass ->
            buildModuleGetCall(moduleClass, builder)
        }

        // Find listOf function
        val listOfFunction = context.referenceFunctions(
            CallableId(FqName("kotlin.collections"), Name.identifier("listOf"))
        ).firstOrNull { func ->
            func.owner.valueParameters.size == 1 &&
            func.owner.valueParameters[0].varargElementType != null
        }?.owner ?: return super.visitCall(expression)

        // Create listOf(module1, module2, ...) or emptyList()
        val modulesListArg = if (moduleExpressions.isNotEmpty()) {
            builder.irCall(listOfFunction.symbol).apply {
                putTypeArgument(0, koinModuleClass.defaultType)
                putValueArgument(0, builder.irVararg(
                    koinModuleClass.defaultType,
                    moduleExpressions
                ))
            }
        } else {
            // Create emptyList<Module>()
            val emptyListFunction = context.referenceFunctions(
                CallableId(FqName("kotlin.collections"), Name.identifier("emptyList"))
            ).firstOrNull()?.owner ?: return super.visitCall(expression)

            builder.irCall(emptyListFunction.symbol).apply {
                putTypeArgument(0, koinModuleClass.defaultType)
            }
        }

        // Create call to implementation: startKoinWith(listOf(...), lambda)
        // For withConfiguration, we also need to pass the extension receiver (KoinApplication instance)
        return builder.irCall(implFunction.symbol).apply {
            if (isWithConfiguration) {
                // withConfigurationWith is an extension on KoinApplication, preserve the receiver
                extensionReceiver = expression.extensionReceiver
            }
            putValueArgument(0, modulesListArg)
            putValueArgument(1, lambdaArg)
        }
    }

    /**
     * Extract module classes from @KoinApplication annotation.
     *
     * Combines:
     * 1. Explicit modules from @KoinApplication(modules = [MyModule::class, ...])
     * 2. Auto-discovered @Configuration modules filtered by configuration labels
     *
     * Configuration label filtering:
     * - @KoinApplication(configurations = ["test"]) → only @Configuration("test") modules
     * - @KoinApplication() or @KoinApplication(configurations = []) → only @Configuration() (default) modules
     */
    private fun extractModulesFromKoinApplicationAnnotation(appClass: IrClass): List<IrClass> {
        val explicitModules = extractExplicitModules(appClass)
        val configurationLabels = extractConfigurationLabels(appClass)

        KoinPluginLogger.debug { "  -> Configuration labels from @KoinApplication: $configurationLabels" }

        // Discover modules filtered by configuration labels
        val discoveredModules = discoverConfigurationModules(configurationLabels)

        // Combine explicit modules with auto-discovered @Configuration modules
        val allModules = (explicitModules + discoveredModules)
            .distinctBy { it.fqNameWhenAvailable }

        return allModules
    }

    /**
     * Extract configuration labels from @KoinApplication annotation.
     * @KoinApplication(configurations = ["test", "prod"]) -> ["test", "prod"]
     * @KoinApplication() or @KoinApplication(configurations = []) -> ["default"]
     */
    private fun extractConfigurationLabels(appClass: IrClass): List<String> {
        val koinAppAnnotation = appClass.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == "org.koin.core.annotation.KoinApplication"
        } ?: return listOf(KoinConfigurationRegistry.DEFAULT_LABEL)

        // configurations is the first parameter in @KoinApplication(configurations, modules)
        val configurationsArg = koinAppAnnotation.getValueArgument(0)

        val labels = mutableListOf<String>()
        when (configurationsArg) {
            is IrVararg -> {
                for (element in configurationsArg.elements) {
                    when (element) {
                        is IrConst -> {
                            val value = element.value
                            if (value is String) {
                                labels.add(value)
                            }
                        }
                        else -> {}
                    }
                }
            }
            is IrConst -> {
                val value = configurationsArg.value
                if (value is String) {
                    labels.add(value)
                }
            }
            else -> {}
        }

        // Default to "default" label if no labels specified
        return labels.ifEmpty { listOf(KoinConfigurationRegistry.DEFAULT_LABEL) }
    }

    /**
     * Discover @Configuration modules filtered by configuration labels.
     * Combines local modules and modules from hint functions.
     */
    private fun discoverConfigurationModules(labels: List<String>): List<IrClass> {
        val localModules = discoverLocalConfigurationModules(labels)
        val hintModules = discoverModulesFromHints(labels)
        return (localModules + hintModules).distinctBy { it.fqNameWhenAvailable }
    }

    /**
     * Extract explicitly listed modules from @KoinApplication(modules = [...])
     */
    private fun extractExplicitModules(appClass: IrClass): List<IrClass> {
        val koinAppAnnotation = appClass.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == "org.koin.core.annotation.KoinApplication"
        } ?: return emptyList()

        // modules is the second parameter in @KoinApplication(configurations, modules)
        val modulesArg = koinAppAnnotation.getValueArgument(1) ?: return emptyList()

        // The argument should be a vararg/array of KClass references
        return when (modulesArg) {
            is IrVararg -> modulesArg.elements.mapNotNull { element ->
                when (element) {
                    is IrClassReference -> (element.classType.classifierOrNull as? IrClassSymbol)?.owner
                    is IrExpression -> extractClassFromKClassExpression(element)
                    else -> null
                }
            }
            is IrClassReference -> listOfNotNull((modulesArg.classType.classifierOrNull as? IrClassSymbol)?.owner)
            else -> emptyList()
        }.filter { it.fqNameWhenAvailable?.asString() != "kotlin.Unit" } // Filter out default Unit::class
    }

    /**
     * Discover @Configuration modules in the current compilation unit.
     * Filters by configuration labels - a module is included if it has ANY of the requested labels.
     *
     * @param labels Configuration labels to filter by
     */
    private fun discoverLocalConfigurationModules(labels: List<String>): List<IrClass> {
        val modules = mutableListOf<IrClass>()

        moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (hasConfigurationWithMatchingLabels(declaration, labels)) {
                    modules.add(declaration)
                }
                super.visitClass(declaration)
            }
        })

        KoinPluginLogger.debug { "  -> Found ${modules.size} local @Configuration modules matching labels $labels" }
        return modules
    }

    /**
     * Discover @Configuration modules from hint functions in dependencies.
     * Uses label-specific function names (configuration_<label>) for filtering.
     *
     * @param labels Configuration labels to filter by
     */
    private fun discoverModulesFromHints(labels: List<String>): List<IrClass> {
        val modules = mutableListOf<IrClass>()

        try {
            // Strategy 1: Local hints (from moduleFragment - same compilation)
            // Look for label-specific hint functions
            for (file in moduleFragment.files) {
                if (file.packageFqName == hintsPackage) {
                    for (declaration in file.declarations) {
                        if (declaration is IrSimpleFunction) {
                            val functionLabel = KoinModuleFirGenerator.labelFromHintFunctionName(declaration.name.asString())
                            if (functionLabel != null && labels.contains(functionLabel)) {
                                val paramType = declaration.valueParameters.firstOrNull()?.type
                                val moduleClass = (paramType?.classifierOrNull as? IrClassSymbol)?.owner
                                if (moduleClass != null && moduleClass !in modules) {
                                    KoinPluginLogger.debug { "  -> Found hint module from local: ${moduleClass.fqNameWhenAvailable} (label=$functionLabel)" }
                                    modules.add(moduleClass)
                                }
                            }
                        }
                    }
                }
            }

            // Strategy 2: Use registry populated by FIR phase
            // FIR discovers modules via symbolProvider and stores in System property with labels
            val registryModules = KoinConfigurationRegistry.getModuleClassNamesForLabels(labels)
            KoinPluginLogger.debug { "  -> Registry has ${registryModules.size} modules for labels $labels" }
            for (moduleClassName in registryModules) {
                val moduleClassId = ClassId.topLevel(FqName(moduleClassName))
                val moduleClassSymbol = context.referenceClass(moduleClassId)
                val moduleClass = moduleClassSymbol?.owner
                if (moduleClass != null && moduleClass !in modules) {
                    KoinPluginLogger.debug { "  -> Found hint module from registry: $moduleClassName" }
                    modules.add(moduleClass)
                }
            }
        } catch (e: Exception) {
            KoinPluginLogger.debug { "  -> Error during hint discovery: ${e.message}" }
        }

        return modules
    }

    /**
     * Check if a class has @Module and @Configuration annotations with matching labels.
     * A module matches if it has ANY of the requested labels.
     *
     * @param declaration The class to check
     * @param labels Configuration labels to match against
     */
    private fun hasConfigurationWithMatchingLabels(declaration: IrClass, labels: List<String>): Boolean {
        val hasModule = declaration.annotations.any {
            it.type.classFqName?.asString() == moduleFqName.asString()
        }
        if (!hasModule) return false

        val configAnnotation = declaration.annotations.firstOrNull {
            it.type.classFqName?.asString() == configurationFqName.asString()
        } ?: return false

        // Extract labels from @Configuration annotation
        val moduleLabels = extractLabelsFromConfigurationAnnotation(configAnnotation)

        // Check if any of the module's labels match the requested labels
        return moduleLabels.any { it in labels }
    }

    /**
     * Extract labels from @Configuration annotation in IR.
     */
    private fun extractLabelsFromConfigurationAnnotation(annotation: IrConstructorCall): List<String> {
        val labels = mutableListOf<String>()

        // @Configuration uses vararg value: String
        val valueArg = annotation.getValueArgument(0)
        when (valueArg) {
            is IrVararg -> {
                for (element in valueArg.elements) {
                    when (element) {
                        is IrConst -> {
                            val value = element.value
                            if (value is String) {
                                labels.add(value)
                            }
                        }
                        else -> {}
                    }
                }
            }
            is IrConst -> {
                val value = valueArg.value
                if (value is String) {
                    labels.add(value)
                }
            }
            else -> {}
        }

        // Default to "default" label if no labels specified
        return labels.ifEmpty { listOf(KoinConfigurationRegistry.DEFAULT_LABEL) }
    }

    /**
     * Extract class from KClass expression (e.g., MyClass::class wrapped in GetClass)
     */
    private fun extractClassFromKClassExpression(expression: IrExpression): IrClass? {
        return when (expression) {
            is IrClassReference -> (expression.classType.classifierOrNull as? IrClassSymbol)?.owner
            is IrGetClass -> (expression.argument.type.classifierOrNull as? IrClassSymbol)?.owner
            else -> null
        }
    }

    /**
     * Build: ModuleClass().module() for classes, or ModuleObject.module() for objects
     */
    private fun buildModuleGetCall(
        moduleClass: IrClass,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        // For Kotlin objects, use the object instance directly (no constructor call)
        // For classes, create an instance via primary constructor
        val instanceExpression = if (moduleClass.isObject) {
            builder.irGetObject(moduleClass.symbol)
        } else {
            val constructor = moduleClass.primaryConstructor ?: return null
            builder.irCallConstructor(constructor.symbol, emptyList())
        }

        // Try to find the function in the current module fragment (same compilation)
        val moduleFunction = findModuleFunction(moduleClass)
        if (moduleFunction != null) {
            KoinPluginLogger.debug { "  -> Found module() function for ${moduleClass.name}" }
            return builder.irCall(moduleFunction.symbol).apply {
                extensionReceiver = instanceExpression
            }
        }

        // Fall back to finding the function via context (for compiled dependencies)
        val function = findModuleFunctionViaContext(moduleClass)
        if (function != null) {
            KoinPluginLogger.debug { "  -> Using module() function from context for ${moduleClass.name}" }
            return builder.irCall(function.symbol).apply {
                extensionReceiver = instanceExpression
            }
        }

        KoinPluginLogger.debug { "  -> ERROR: Could not find module() function for ${moduleClass.name}" }
        return null
    }

    /**
     * Find the module extension function for a given module class.
     * Searches the current module fragment (for IR-generated functions in same compilation).
     */
    private fun findModuleFunction(moduleClass: IrClass): IrSimpleFunction? {
        val targetFqName = moduleClass.fqNameWhenAvailable?.asString()

        // Try to find in current module fragment (IR-generated in same compilation)
        for (file in moduleFragment.files) {
            for (func in file.declarations.filterIsInstance<IrSimpleFunction>()) {
                if (func.name.asString() != "module") continue
                val receiverFqName = func.extensionReceiverParameter?.type?.classFqName?.asString()
                if (receiverFqName == targetFqName) {
                    KoinPluginLogger.debug { "  -> Found module() function in module fragment for $targetFqName" }
                    return func
                }
            }
        }

        // Function not in current compilation - it might be from a dependency
        return null
    }

    /**
     * Find the module function for a given module class via context.
     * This is the fallback for compiled dependencies where the function can't be found in moduleFragment.
     */
    private fun findModuleFunctionViaContext(moduleClass: IrClass): IrSimpleFunction? {
        val packageName = moduleClass.fqNameWhenAvailable?.parent() ?: FqName.ROOT

        // First try direct lookup via referenceFunctions
        val functionCandidates = context.referenceFunctions(
            CallableId(packageName, Name.identifier("module"))
        )
        KoinPluginLogger.debug { "  -> Searching for module function in $packageName, found ${functionCandidates.size} candidates" }

        // Log all candidates for debugging
        for ((idx, func) in functionCandidates.withIndex()) {
            val owner = func.owner
            val extensionReceiverType = owner.extensionReceiverParameter?.type
            val receiverFqName = (extensionReceiverType?.classifierOrNull as? IrClassSymbol)?.owner?.fqNameWhenAvailable
            val parent = owner.parent
            val parentInfo = when (parent) {
                is IrFile -> "file=${parent.name}"
                is IrClass -> "facade=${parent.name}"
                else -> "parent=${parent.javaClass.simpleName}"
            }
            val origin = owner.origin.toString()
            val symbolId = System.identityHashCode(func)
            KoinPluginLogger.debug { "    -> Candidate $idx: receiver=$receiverFqName, $parentInfo, origin=$origin, symbolId=$symbolId" }
        }

        // Filter for functions with matching receiver
        // (Note: there may be duplicates if function was relocated during IR - metadata is stale)
        val matchingCandidates = functionCandidates.filter { func ->
            val extensionReceiverType = func.owner.extensionReceiverParameter?.type
            val receiverFqName = (extensionReceiverType?.classifierOrNull as? IrClassSymbol)?.owner?.fqNameWhenAvailable
            receiverFqName == moduleClass.fqNameWhenAvailable
        }

        KoinPluginLogger.debug { "    -> Found ${matchingCandidates.size} matching candidates for ${moduleClass.name}" }

        // If we have duplicates, try to select the correct one based on the module class's
        // containing file. The module() function should be in the same file facade class
        // as the module class definition.
        val result = if (matchingCandidates.size > 1) {
            selectCorrectCandidate(matchingCandidates, moduleClass, packageName)
        } else {
            matchingCandidates.firstOrNull()?.owner
        }

        if (result != null) {
            val parentFileName = (result.parent as? IrFile)?.name ?: "unknown"
            KoinPluginLogger.debug { "  -> Selected function for ${moduleClass.name} in file: $parentFileName" }
            return result
        }

        // Fallback: Try to find the function via the file facade class
        // When functions are relocated during IR, the FIR metadata may not reflect the new location
        // We try to find the file facade class (e.g., ConfigLabelsModuleKt for ConfigLabelsModule.kt)
        // and search for the module() function there
        return findModuleFunctionViaFileFacade(moduleClass, packageName)
    }

    /**
     * When we have duplicate candidates (stale FIR metadata), select the correct one
     * by looking up the file facade class that actually contains the function in bytecode.
     */
    private fun selectCorrectCandidate(
        candidates: List<IrFunctionSymbol>,
        moduleClass: IrClass,
        packageName: FqName
    ): IrSimpleFunction? {
        // Try to get the expected file facade from the module class's source file
        val containingFile = moduleClass.parent as? IrFile
        if (containingFile != null) {
            val fileName = containingFile.name
            if (fileName.isNotEmpty()) {
                val baseName = fileName.removeSuffix(".kt")
                val expectedFacadeClassName = "${baseName}Kt"
                KoinPluginLogger.debug { "    -> Expected file facade from source: ${packageName.asString()}.$expectedFacadeClassName" }

                val facadeClassId = ClassId(packageName, Name.identifier(expectedFacadeClassName))
                val facadeClassSymbol = context.referenceClass(facadeClassId)
                if (facadeClassSymbol != null) {
                    val facadeClass = facadeClassSymbol.owner
                    val moduleFunction = facadeClass.functions.firstOrNull { func ->
                        func.name.asString() == "module" &&
                        func.extensionReceiverParameter?.type?.classFqName == moduleClass.fqNameWhenAvailable
                    }
                    if (moduleFunction != null) {
                        KoinPluginLogger.debug { "    -> Found correct function in file facade class" }
                        return moduleFunction
                    }
                }
            }
        }

        // For classes from JARs, we have duplicate candidates in the symbol table
        // This happens when FIR metadata has stale entries from earlier compilation phases
        KoinPluginLogger.debug { "    -> Module class is from JAR, ${candidates.size} duplicate candidates" }

        // Try to find one from current compilation first (parent is IrFile)
        for (candidate in candidates) {
            val owner = candidate.owner as? IrSimpleFunction ?: continue
            if (owner.parent is IrFile) {
                KoinPluginLogger.debug { "    -> Using candidate from current compilation" }
                return owner
            }
        }

        // For JAR classes, the function should be in a file facade class
        // Try to find the correct candidate by matching file facade names
        // The module class was defined in a file, and the module() function is in that file's facade

        // Try to get the expected file facade from the module class's source file info
        // For JAR classes, check if we can get the file name from metadata
        val moduleClassName = moduleClass.name.asString()

        // Log each candidate's parent info for debugging
        for ((idx, candidate) in candidates.withIndex()) {
            val owner = candidate.owner as? IrSimpleFunction ?: continue
            val parent = owner.parent
            when (parent) {
                is IrClass -> KoinPluginLogger.debug { "    -> Candidate $idx parent facade: ${parent.name}" }
                is IrFile -> KoinPluginLogger.debug { "    -> Candidate $idx parent file: ${parent.name}" }
                else -> KoinPluginLogger.debug { "    -> Candidate $idx parent: ${parent.javaClass.simpleName}" }
            }
        }

        // For JAR classes with IrExternalPackageFragmentImpl parent, we can't determine which
        // candidate is correct from the parent info. Instead, look up the file facade class
        // directly and get the function from there.
        //
        // Strategy: Try to find the module() function by looking up possible file facade classes
        // in the package and checking which one contains a function with the right receiver.

        // Try class-based facade name first (MyModule -> MyModuleKt)
        val classBasedFacadeName = "${moduleClassName}Kt"
        val classBasedFunc = lookupFunctionInFacade(packageName, classBasedFacadeName, moduleClass)
        if (classBasedFunc != null) {
            KoinPluginLogger.debug { "    -> Found function in class-based facade: $classBasedFacadeName" }
            return classBasedFunc
        }

        // Try all known facade names in the package by iterating through candidates
        // and extracting unique facade class names from them
        val triedFacades = mutableSetOf(classBasedFacadeName)

        // Try some common patterns
        val commonFacadePatterns = listOf(
            "${moduleClassName}TestKt",
            "${moduleClassName.removeSuffix("Module")}TestKt",
            "${moduleClassName}ConfigTestKt"
        )

        for (facadeName in commonFacadePatterns) {
            if (facadeName in triedFacades) continue
            triedFacades.add(facadeName)

            val func = lookupFunctionInFacade(packageName, facadeName, moduleClass)
            if (func != null) {
                KoinPluginLogger.debug { "    -> Found function in pattern-based facade: $facadeName" }
                return func
            }
        }

        // Fallback: Since we can't find the facade class, just try using the last candidate
        // The duplicates typically come from stale FIR metadata, and empirically the last one
        // tends to be from the more recent compilation
        KoinPluginLogger.debug { "    -> No function found via facade lookup, trying last candidate" }
        return candidates.lastOrNull()?.owner as? IrSimpleFunction
    }

    /**
     * Look up a module() function in a specific file facade class.
     */
    private fun lookupFunctionInFacade(
        packageName: FqName,
        facadeName: String,
        moduleClass: IrClass
    ): IrSimpleFunction? {
        val facadeClassId = ClassId(packageName, Name.identifier(facadeName))
        val facadeClassSymbol = try {
            context.referenceClass(facadeClassId)
        } catch (e: Throwable) {
            KoinPluginLogger.debug { "      -> Exception looking up $facadeName: ${e.message}" }
            return null
        }
        if (facadeClassSymbol == null) {
            KoinPluginLogger.debug { "      -> Facade class $facadeName not found" }
            return null
        }

        val facadeClass = facadeClassSymbol.owner
        val allFunctions = facadeClass.functions.toList()
        val moduleFunctions = allFunctions.filter { it.name.asString() == "module" }

        if (moduleFunctions.isNotEmpty()) {
            KoinPluginLogger.debug { "      -> $facadeName has ${moduleFunctions.size} module() functions" }
            for (func in moduleFunctions) {
                val receiverType = func.extensionReceiverParameter?.type?.classFqName
                KoinPluginLogger.debug { "        -> module() receiver: $receiverType (want: ${moduleClass.fqNameWhenAvailable})" }
            }
        }

        return moduleFunctions.firstOrNull { func ->
            func.extensionReceiverParameter?.type?.classFqName == moduleClass.fqNameWhenAvailable
        }
    }

    /**
     * Try to find the module() function by looking at the file facade class.
     * This is needed when functions are relocated during IR and FIR metadata is stale.
     */
    private fun findModuleFunctionViaFileFacade(moduleClass: IrClass, packageName: FqName): IrSimpleFunction? {
        KoinPluginLogger.debug { "  -> Trying file facade lookup for ${moduleClass.name}" }
        KoinPluginLogger.debug { "    -> moduleClass.parent type: ${moduleClass.parent.javaClass.simpleName}" }

        // Get the source file name from the module class
        val containingFile = moduleClass.parent as? IrFile
        if (containingFile == null) {
            KoinPluginLogger.debug { "    -> moduleClass.parent is not IrFile, trying to find file via metadata" }
            // For classes from JARs, we need a different approach
            // Try to find the file facade class based on naming convention
            return findModuleFunctionViaClassName(moduleClass, packageName)
        }
        val fileName = containingFile.name
        if (fileName.isEmpty()) {
            KoinPluginLogger.debug { "    -> fileName is empty" }
            return null
        }

        // Construct the file facade class name: FileName.kt -> FileNameKt
        val baseName = fileName.removeSuffix(".kt")
        val facadeClassName = "${baseName}Kt"
        val facadeClassId = ClassId(packageName, Name.identifier(facadeClassName))

        KoinPluginLogger.debug { "  -> Trying file facade class: ${facadeClassId.asFqNameString()}" }

        // Look up the file facade class
        val facadeClassSymbol = context.referenceClass(facadeClassId)
        if (facadeClassSymbol == null) {
            KoinPluginLogger.debug { "  -> File facade class not found" }
            return null
        }

        // Search the facade class for the module() function with the right receiver
        val facadeClass = facadeClassSymbol.owner
        val moduleFunction = facadeClass.functions.firstOrNull { func ->
            func.name.asString() == "module" &&
            func.extensionReceiverParameter?.type?.classFqName == moduleClass.fqNameWhenAvailable
        }

        if (moduleFunction != null) {
            KoinPluginLogger.debug { "  -> Found module() in file facade class" }
        }
        return moduleFunction
    }

    /**
     * For classes from JARs where we can't get the source file name,
     * try common naming conventions to find the module() function.
     */
    private fun findModuleFunctionViaClassName(moduleClass: IrClass, packageName: FqName): IrSimpleFunction? {
        val className = moduleClass.name.asString()

        // Try common file facade class names:
        // 1. {ClassName}Kt - if class has its own file
        // 2. {ClassName}ModuleKt - common naming pattern
        val possibleFacadeNames = listOf(
            "${className}Kt",                    // ConfigLabelsModule -> ConfigLabelsModuleKt
            "${className.removeSuffix("Module")}ModuleKt",  // ConfigLabelsModule -> ConfigLabelsKt
        )

        for (facadeName in possibleFacadeNames) {
            val facadeClassId = ClassId(packageName, Name.identifier(facadeName))
            KoinPluginLogger.debug { "    -> Trying facade class: ${facadeClassId.asFqNameString()}" }

            val facadeClassSymbol = context.referenceClass(facadeClassId)
            if (facadeClassSymbol == null) {
                continue
            }

            val facadeClass = facadeClassSymbol.owner
            val moduleFunction = facadeClass.functions.firstOrNull { func ->
                func.name.asString() == "module" &&
                func.extensionReceiverParameter?.type?.classFqName == moduleClass.fqNameWhenAvailable
            }

            if (moduleFunction != null) {
                KoinPluginLogger.debug { "    -> Found module() in ${facadeName}" }
                return moduleFunction
            }
        }

        return null
    }
}
