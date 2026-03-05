package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.koin.compiler.plugin.KoinPluginConstants
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
    private val moduleFragment: IrModuleFragment,
    private val annotationProcessor: KoinAnnotationProcessor? = null,
    private val safetyValidator: CompileSafetyValidator? = null
) : IrElementTransformerVoid() {

    // Koin types
    private val koinModuleClassId = ClassId.topLevel(FqName("org.koin.core.module.Module"))

    // Annotation FQName
    private val moduleFqName = FqName("org.koin.core.annotation.Module")

    // Hint package for cross-module discovery (label-specific function names)
    private val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

    // Module function resolver (multi-strategy lookup for module() extension functions)
    private val moduleFunctionResolver = ModuleFunctionResolver(context, moduleFragment)

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

        // A3: Validate the full assembled graph at the startKoin entry point
        if (safetyValidator != null && moduleClasses.isNotEmpty() && annotationProcessor != null) {
            safetyValidator.validateFullGraph(
                appClass.name.asString(),
                moduleClasses,
                annotationProcessor.collectedModuleClasses,
                annotationProcessor::getDefinitionsForModule,
                annotationProcessor::getDefinitionsForDependencyModule
            )
        }

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
            moduleFunctionResolver.buildModuleGetCall(moduleClass, builder)
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
        } ?: return listOf(KoinPluginConstants.DEFAULT_LABEL)

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
        return labels.ifEmpty { listOf(KoinPluginConstants.DEFAULT_LABEL) }
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
     * Discover @Configuration modules from hint functions (local + dependencies).
     * Queries configuration_<label> hint functions via context.referenceFunctions(),
     * which sees both local FIR-generated hints and dependency hints from klib/JAR metadata.
     *
     * @param labels Configuration labels to filter by
     */
    private fun discoverModulesFromHints(labels: List<String>): List<IrClass> {
        val modules = mutableListOf<IrClass>()

        try {
            for (label in labels) {
                val callableId = CallableId(hintsPackage, KoinModuleFirGenerator.hintFunctionNameForLabel(label))
                val hintFunctions = context.referenceFunctions(callableId)

                KoinPluginLogger.debug { "  -> Hint query for label '$label': ${hintFunctions.count()} functions" }

                for (hintFuncSymbol in hintFunctions) {
                    val hintFunc = hintFuncSymbol.owner
                    val paramType = hintFunc.valueParameters.firstOrNull()?.type
                    val moduleClass = (paramType?.classifierOrNull as? IrClassSymbol)?.owner
                    if (moduleClass != null && moduleClass !in modules) {
                        KoinPluginLogger.debug { "  -> Found hint module: ${moduleClass.fqNameWhenAvailable} (label=$label)" }
                        modules.add(moduleClass)
                    }
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
     */
    private fun hasConfigurationWithMatchingLabels(declaration: IrClass, labels: List<String>): Boolean {
        val hasModule = declaration.annotations.any {
            it.type.classFqName?.asString() == moduleFqName.asString()
        }
        if (!hasModule) return false

        return extractConfigurationLabels(declaration).any { it in labels }
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

}
