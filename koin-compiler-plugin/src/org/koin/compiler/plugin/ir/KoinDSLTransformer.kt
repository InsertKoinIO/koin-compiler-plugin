package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger
import org.jetbrains.kotlin.ir.expressions.IrGetField

/**
 * Transforms Koin DSL calls:
 *
 * 1. Reified type parameter syntax (single<T>(), factory<T>(), etc.):
 *    single<MyClass>() -> single(MyClass::class, null) { MyClass(get(), get()) }
 *
 * 2. Constructor reference for create only:
 *    scope.create(::MyClass) -> MyClass(scope.get(), scope.get())
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class KoinDSLTransformer(
    private val context: IrPluginContext,
    private val lookupTracker: LookupTracker? = null
) : IrElementTransformerVoid() {

    private val unsafeDslChecksEnabled = KoinPluginLogger.unsafeDslChecksEnabled
    private val compileSafetyEnabled = KoinPluginLogger.compileSafetyEnabled
    private var currentFile: IrFile? = null

    // ── Collected DSL definitions (for A3 full-graph validation) ──
    private val _dslDefinitions = mutableListOf<Definition.DslDef>()
    val dslDefinitions: List<Definition.DslDef> get() = _dslDefinitions

    // ── Collected call-site validations (replaces KoinCallSiteValidator tree walk) ──
    private val _pendingCallSites = mutableListOf<PendingCallSiteValidation>()
    val collectedCallSites: List<PendingCallSiteValidation> get() = _pendingCallSites

    // ── Module loading graph (for DSL module loading validation) ──
    private val _moduleIncludes = mutableMapOf<String, MutableList<String>>()
    val moduleIncludes: Map<String, List<String>> get() = _moduleIncludes

    private val _startKoinModules = mutableListOf<String>()
    val startKoinModules: List<String> get() = _startKoinModules

    override fun visitFile(declaration: IrFile): IrFile {
        currentFile = declaration
        return super.visitFile(declaration)
    }

    // Qualifier extraction helper
    private val qualifierExtractor = QualifierExtractor(context)

    // Reuse argument generator and lambda builder from the annotation processor infrastructure
    private val argumentGenerator = KoinArgumentGenerator(context, qualifierExtractor)
    private val lambdaBuilder = LambdaBuilder(context, qualifierExtractor, argumentGenerator)

    private val createName = Name.identifier("create")
    private val singleName = Name.identifier("single")
    private val factoryName = Name.identifier("factory")
    private val scopedName = Name.identifier("scoped")
    private val viewModelName = Name.identifier("viewModel")
    private val workerName = Name.identifier("worker")

    // Mapping from stub function names to target (build*) function names
    private val targetFunctionNames = mapOf(
        singleName to Name.identifier("buildSingle"),
        factoryName to Name.identifier("buildFactory"),
        scopedName to Name.identifier("buildScoped"),
        viewModelName to Name.identifier("buildViewModel"),
        workerName to Name.identifier("buildWorker")
    )

    // Cached class lookups (avoid repeated referenceClass calls)
    private val kClassClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KCLASS))?.owner }

    // Cache for target functions (buildSingle, buildFactory, etc.)
    private val targetFunctionCache = mutableMapOf<Pair<Name, String>, IrSimpleFunction?>()

    /**
     * Context passed through the transformation to track the current position in the tree.
     * Using immutable data class with stack-based save/restore pattern for cleaner state management.
     *
     * @property function The enclosing function being visited
     * @property lambda The enclosing lambda (for create() validation)
     * @property definitionCall The enclosing DSL definition call (single/factory/scoped/etc.)
     * @property scopeTypeClass The scope type when inside a scope<ScopeType> { } block
     */
    private data class TransformContext(
        val function: IrFunction? = null,
        val lambda: IrSimpleFunction? = null,
        val definitionCall: Name? = null,
        val scopeTypeClass: IrClass? = null,
        val createQualifier: QualifierValue? = null,
        val createReturnClass: IrClass? = null,
        val modulePropertyId: String? = null
    )

    // Stack-based context management (thread-safe for single-threaded compiler)
    private var transformContext = TransformContext()

    // Convenience accessors for cleaner code
    private val currentFunction: IrFunction? get() = transformContext.function
    private val currentLambda: IrSimpleFunction? get() = transformContext.lambda
    private val currentDefinitionCall: Name? get() = transformContext.definitionCall

    override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
        return withContext(transformContext.copy(lambda = expression.function)) {
            super.visitFunctionExpression(expression)
        }
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        return withContext(transformContext.copy(function = declaration)) {
            super.visitFunction(declaration)
        }
    }

    override fun visitProperty(declaration: IrProperty): IrStatement {
        if (!compileSafetyEnabled) return super.visitProperty(declaration)
        val backingField = declaration.backingField
        val isModuleType = backingField?.type?.classFqName?.asString() == "org.koin.core.module.Module"
        if (isModuleType) {
            val propertyId = buildModulePropertyId(declaration)
            if (propertyId != null) {
                KoinPluginLogger.debug { "Module property: $propertyId" }
                return withContext(transformContext.copy(modulePropertyId = propertyId)) {
                    super.visitProperty(declaration)
                }
            }
        }
        return super.visitProperty(declaration)
    }

    private fun buildModulePropertyId(property: IrProperty): String? {
        val parent = property.parent
        val packageName = when (parent) {
            is IrFile -> parent.packageFqName.asString()
            is IrClass -> parent.fqNameWhenAvailable?.asString()
            is IrPackageFragment -> parent.packageFqName.asString()
            else -> null
        } ?: return null
        return if (packageName.isEmpty()) property.name.asString()
        else "$packageName.${property.name.asString()}"
    }

    /**
     * Run [block] with a scoped [TransformContext], restoring the previous context afterward.
     * Qualifier propagation from inner create(::T) is preserved across the boundary so that
     * the enclosing definition call (single/factory/etc.) can pick it up.
     */
    private inline fun <T> withContext(newContext: TransformContext, block: () -> T): T {
        val previousContext = transformContext
        transformContext = newContext
        val result = block()
        val innerQualifier = transformContext.createQualifier
        val innerReturnClass = transformContext.createReturnClass
        transformContext = previousContext
        if (innerQualifier != null) {
            transformContext = transformContext.copy(
                createQualifier = innerQualifier,
                createReturnClass = innerReturnClass
            )
        }
        return result
    }

    // DSL definition function names to track
    private val definitionNames = setOf(singleName, factoryName, scopedName, viewModelName, workerName)

    // Mapping from function names to DefinitionType
    private val definitionTypeMap = mapOf(
        singleName to DefinitionType.SINGLE,
        factoryName to DefinitionType.FACTORY,
        scopedName to DefinitionType.SCOPED,
        viewModelName to DefinitionType.VIEW_MODEL,
        workerName to DefinitionType.WORKER
    )

    // FQ name strings of call-site resolution functions to intercept
    private val callSiteResolutionFqNames: Set<String> =
        KoinAnnotationFqNames.CALL_SITE_RESOLUTION_FUNCTIONS.map { it.asString() }.toSet()

    // Scope function name for detecting scope<ScopeType> { } blocks
    private val scopeName = Name.identifier("scope")

    /**
     * Visit class declarations to collect call-site validations from member property delegates
     * (e.g., `by inject()`, `by viewModel()`). The default transformer doesn't traverse
     * backing field initializers of class properties.
     */
    override fun visitClass(declaration: IrClass): IrStatement {
        if (compileSafetyEnabled) {
            for (decl in declaration.declarations) {
                if (decl is IrProperty) {
                    val initializer = decl.backingField?.initializer
                    if (initializer != null) {
                        collectCallSitesFromExpression(initializer.expression)
                    }
                }
            }
        }
        return super.visitClass(declaration)
    }

    /**
     * Recursively scan an expression tree for call-site resolution functions.
     * Used for class property initializers which aren't visited by the standard transformer.
     */
    private fun collectCallSitesFromExpression(expression: IrExpression) {
        if (expression is IrCall) {
            val callee = expression.symbol.owner
            collectCallSiteIfResolutionFunction(expression, callee)
            // Recurse into call arguments
            for (i in 0 until expression.valueArgumentsCount) {
                val arg = expression.getValueArgument(i)
                if (arg != null) collectCallSitesFromExpression(arg)
            }
            expression.extensionReceiver?.let { collectCallSitesFromExpression(it) }
            expression.dispatchReceiver?.let { collectCallSitesFromExpression(it) }
        }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        val functionName = callee.name

        // Collect call-site validations (koinViewModel<T>(), get<T>(), inject<T>(), etc.)
        if (compileSafetyEnabled) {
            collectCallSiteIfResolutionFunction(expression, callee)
            collectModuleLoadingInfo(expression, callee)
        }

        // Track if we're entering a Koin DSL definition call (single, factory, scoped, etc.)
        val previousContext = transformContext

        if (functionName in definitionNames) {
            transformContext = transformContext.copy(definitionCall = functionName)
        }

        // Detect scope<ScopeType> { } — push scope type into context
        if (functionName == scopeName && expression.typeArgumentsCount >= 1) {
            val scopeTypeArg = expression.getTypeArgument(0)
            val scopeTypeClass = (scopeTypeArg?.classifierOrNull as? IrClassSymbol)?.owner
            if (scopeTypeClass != null) {
                transformContext = transformContext.copy(scopeTypeClass = scopeTypeClass)
            }
        }

        val transformedCall = super.visitCall(expression) as IrCall

        // Capture qualifier propagated from inner create(::T) before restoring context
        val propagatedQualifier = transformContext.createQualifier
        val propagatedReturnClass = transformContext.createReturnClass

        // Restore previous context
        transformContext = previousContext

        // Detect .bind(Interface::class) — add the bound type to the last collected DslDef
        if (compileSafetyEnabled && functionName.asString() == "bind") {
            collectBindType(transformedCall)
        }

        // Only handle our target functions
        if (functionName != createName && functionName != singleName && functionName != factoryName &&
            functionName != scopedName && functionName != viewModelName && functionName != workerName) {
            return transformedCall
        }

        // Get receiver - can be extension receiver or dispatch receiver (for implicit this in lambdas)
        val extensionReceiver = transformedCall.extensionReceiver
        val dispatchReceiver = transformedCall.dispatchReceiver

        // Determine the actual receiver
        val receiver = extensionReceiver ?: dispatchReceiver ?: return transformedCall

        // Receiver must be from Koin package
        val receiverClassifier = receiver.type.classifierOrNull?.owner as? IrClass ?: return transformedCall
        val receiverPackage = receiverClassifier.packageFqName?.asString()
        if (receiverPackage == null || (!receiverPackage.startsWith("org.koin.core") && !receiverPackage.startsWith("org.koin.dsl"))) {
            return transformedCall
        }

        // Propagate qualifier from create(::ref) to enclosing definition call
        // When single { create(::qualifiedFunc) } is used, the qualifier from the function
        // must be applied to the single definition registration
        if (functionName in definitionNames && propagatedQualifier != null) {
            val qualifier = propagatedQualifier
            val returnClass = propagatedReturnClass!!
            return handleDefinitionWithCreateQualifier(
                transformedCall, receiver, receiverClassifier, functionName, returnClass, qualifier
            )
        }

        // Handle reified type parameter syntax: single<T>(), factory<T>(), etc.
        if (transformedCall.valueArgumentsCount == 0 && transformedCall.typeArgumentsCount >= 1 && extensionReceiver != null) {
            return handleTypeParameterCall(transformedCall, extensionReceiver, receiverClassifier, functionName)
        }

        // Handle create(::Constructor) or create(::function) - for Scope.create
        // Works with both extension receiver (scope.create) and dispatch receiver (this.create in lambda)
        if (functionName == createName && receiverClassifier.name.asString() == "Scope") {
            val functionRef = transformedCall.getValueArgument(0) as? IrFunctionReference ?: return transformedCall
            val referencedFunction = functionRef.symbol.owner
            return handleScopeCreate(transformedCall, referencedFunction, receiver)
        }

        return transformedCall
    }

    /**
     * If the call is a Koin resolution function (koinViewModel<T>(), get<T>(), inject<T>(), etc.),
     * collect it as a pending call-site validation.
     */
    private fun collectCallSiteIfResolutionFunction(expression: IrCall, callee: IrSimpleFunction) {
        val calleeFqName = callee.fqNameWhenAvailable?.asString() ?: return
        if (calleeFqName !in callSiteResolutionFqNames) return
        if (callee.typeParameters.isEmpty()) return

        val typeArg = expression.getTypeArgument(0) ?: return
        val targetClass = (typeArg.classifierOrNull as? IrClassSymbol)?.owner ?: return
        val targetFqName = targetClass.fqNameWhenAvailable?.asString() ?: return

        val file = currentFile
        val filePath = file?.fileEntry?.name
        val line = if (file != null && expression.startOffset >= 0) {
            file.fileEntry.getLineNumber(expression.startOffset) + 1
        } else 0
        val column = if (file != null && expression.startOffset >= 0) {
            file.fileEntry.getColumnNumber(expression.startOffset) + 1
        } else 0

        _pendingCallSites.add(PendingCallSiteValidation(
            targetFqName = targetFqName,
            targetClass = targetClass,
            callFunctionName = calleeFqName.substringAfterLast("."),
            filePath = filePath,
            line = line,
            column = column
        ))

        // IC: call site file depends on the target class
        trackClassLookup(lookupTracker, currentFile, targetClass)
    }

    /**
     * Detect .bind(Interface::class) and add the bound type to the last collected DslDef.
     * The inner single<T>()/factory<T>() has already been processed and its DslDef added.
     */
    private fun collectBindType(expression: IrCall) {
        if (_dslDefinitions.isEmpty()) return

        // Extract the KClass argument from bind(clazz: KClass<S>)
        val classArg = expression.getValueArgument(0) ?: return
        val boundClass = when (classArg) {
            is IrClassReference -> {
                val classifier = classArg.classType.classifierOrNull
                when (classifier) {
                    is IrClassSymbol -> classifier.owner
                    is IrClass -> classifier
                    else -> null
                }
            }
            else -> null
        } ?: return

        // Update the last DslDef with the additional binding
        val lastDef = _dslDefinitions.last()
        if (boundClass.fqNameWhenAvailable?.asString() !in lastDef.bindings.mapNotNull { it.fqNameWhenAvailable?.asString() }) {
            _dslDefinitions[_dslDefinitions.lastIndex] = lastDef.copy(
                bindings = lastDef.bindings + boundClass
            )
            KoinPluginLogger.debug { "  bind: ${lastDef.returnTypeClass.name} -> ${boundClass.name}" }
        }
    }

    private fun collectModuleLoadingInfo(expression: IrCall, callee: IrSimpleFunction) {
        val functionName = callee.name.asString()
        if (functionName == "includes") {
            val receiverType = (callee.extensionReceiverParameter ?: callee.dispatchReceiverParameter)
                ?.type?.classFqName?.asString()
            if (receiverType == "org.koin.core.module.Module") {
                val currentModuleId = transformContext.modulePropertyId ?: return
                val includedModules = resolveModuleReferences(expression)
                if (includedModules.isNotEmpty()) {
                    _moduleIncludes.getOrPut(currentModuleId) { mutableListOf() }.addAll(includedModules)
                    KoinPluginLogger.debug { "  includes: $currentModuleId -> $includedModules" }
                }
            }
            return
        }
        if (functionName == "modules") {
            val receiverType = (callee.extensionReceiverParameter ?: callee.dispatchReceiverParameter)
                ?.type?.classFqName?.asString()
            if (receiverType == "org.koin.core.KoinApplication") {
                val loadedModules = resolveModuleReferences(expression)
                if (loadedModules.isNotEmpty()) {
                    _startKoinModules.addAll(loadedModules)
                    KoinPluginLogger.debug { "  modules() at startKoin: $loadedModules" }
                }
            }
        }
    }

    private fun resolveModuleReferences(call: IrCall): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until call.valueArgumentsCount) {
            val arg = call.getValueArgument(i) ?: continue
            resolveModuleRef(arg, result)
        }
        return result
    }

    private fun resolveModuleRef(expression: IrExpression, result: MutableList<String>) {
        when (expression) {
            is IrGetField -> {
                val property = expression.symbol.owner.correspondingPropertySymbol?.owner
                if (property != null) {
                    val propId = buildModulePropertyId(property)
                    if (propId != null) result.add(propId)
                }
            }
            is IrCall -> {
                val calledFunction = expression.symbol.owner
                val property = calledFunction.correspondingPropertySymbol?.owner
                if (property != null) {
                    val propId = buildModulePropertyId(property)
                    if (propId != null) result.add(propId)
                }
            }
            is IrVararg -> {
                for (element in expression.elements) {
                    if (element is IrExpression) resolveModuleRef(element, result)
                }
            }
            else -> {}
        }
    }

    /**
     * Handle single<T>(), factory<T>(), scoped<T>(), viewModel<T>(), worker<T>()
     */
    private fun handleTypeParameterCall(
        call: IrCall,
        extensionReceiver: IrExpression,
        receiverClassifier: IrClass,
        functionName: Name
    ): IrExpression {
        val typeArg = call.getTypeArgument(0) ?: return call
        val targetClass = typeArg.classifierOrNull?.owner as? IrClass ?: return call
        val erasedTargetType = erasedTypeForClass(targetClass)
        val constructorTypeArguments = extractConstructorTypeArguments(typeArg, targetClass)
        val constructor = targetClass.primaryConstructor
        if (constructor == null) {
            KoinPluginLogger.debug { "$functionName<${targetClass.name}>() skipped - no primary constructor" }
            return call
        }

        // IC: file containing DSL call depends on the target class
        trackClassLookup(lookupTracker, currentFile, targetClass)

        // Get qualifier from @Named or @Qualifier annotation on class
        val qualifier = qualifierExtractor.extractFromClass(targetClass)

        // Collect DSL definition for safety validation
        val defType = definitionTypeMap[functionName]
        if (defType != null && compileSafetyEnabled) {
            _dslDefinitions.add(Definition.DslDef(
                irClass = targetClass,
                definitionType = defType,
                bindings = emptyList(), // DSL: only explicit bind() adds bindings (no auto-bind like annotations)
                scopeClass = if (defType == DefinitionType.SCOPED) transformContext.scopeTypeClass else null,
                modulePropertyId = transformContext.modulePropertyId,
                qualifier = qualifier
            ))
        }

        val receiverClassName = receiverClassifier.name.asString()

        // Log the interception
        KoinPluginLogger.user { "Intercepting $functionName<${targetClass.name}>() on $receiverClassName" }

        val builder = DeclarationIrBuilder(context, call.symbol, call.startOffset, call.endOffset)

        // Find target function with KClass parameter
        val targetFunction = findTargetFunction(functionName, receiverClassName)
        if (targetFunction == null) {
            KoinPluginLogger.debug { "$functionName target function not found for $receiverClassName" }
            return call
        }

        // For worker definitions, use class name as qualifier (required by WorkManager)
        val effectiveQualifier: QualifierValue? = if (functionName == workerName) {
            QualifierValue.StringQualifier(targetClass.fqNameWhenAvailable?.asString() ?: targetClass.name.asString())
        } else {
            qualifier
        }

        // Build the transformed call
        return builder.irCall(targetFunction.symbol).apply {
            this.extensionReceiver = extensionReceiver
            putTypeArgument(0, typeArg)

            // Arg 0: KClass<T>
            val kClassClassOwner = kClassClass ?: return call
            putValueArgument(0, IrClassReferenceImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                kClassClassOwner.typeWith(erasedTargetType),
                targetClass.symbol,
                erasedTargetType
            ))

            // Arg 1: Qualifier? (for workers, always use class name as qualifier)
            putValueArgument(1, qualifierExtractor.createQualifierCall(effectiveQualifier, builder) ?: builder.irNull())

            // Arg 2: Definition lambda { T(get(), get(), ...) }
            val parentFunc = currentFunction ?: return call
            putValueArgument(2, lambdaBuilder.create(typeArg, builder, parentFunc) { lb, scopeParam, paramsParam ->
                lb.irCallConstructor(constructor.symbol, constructorTypeArguments).apply {
                    constructor.valueParameters.forEachIndexed { index, param ->
                        val scopeGet = lb.irGet(scopeParam)
                        val paramsGet = lb.irGet(paramsParam)
                        val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeGet, paramsGet, lb)
                        if (argument != null) {
                            putValueArgument(index, argument)
                        }
                    }
                }
            })
        }
    }

    /**
     * Handle Scope.create(::Constructor) or Scope.create(::function)
     * Constructor -> Constructor(get(), get(), ...)
     * Function -> function(get(), get(), ...)
     */
    private fun handleScopeCreate(
        call: IrCall,
        referencedFunction: IrFunction,
        scopeReceiver: IrExpression
    ): IrExpression {
        // Validate that create() is the only instruction in the lambda (if enabled)
        if (unsafeDslChecksEnabled) {
            validateCreateInLambda(call, referencedFunction)
        }

        val builder = DeclarationIrBuilder(context, call.symbol, call.startOffset, call.endOffset)

        return when (referencedFunction) {
            is IrConstructor -> {
                val targetClass = referencedFunction.parent as IrClass
                // IC: file containing create(::T) depends on the target class
                trackClassLookup(lookupTracker, currentFile, targetClass)
                // Extract qualifier from class for propagation to enclosing definition
                val classQualifier = qualifierExtractor.extractFromClass(targetClass)
                if (classQualifier != null && currentDefinitionCall != null) {
                    transformContext = transformContext.copy(createQualifier = classQualifier, createReturnClass = targetClass)
                }
                // Collect DSL definition from create(::T) based on enclosing definition call
                val enclosingDefType = currentDefinitionCall?.let { definitionTypeMap[it] }
                if (enclosingDefType != null && compileSafetyEnabled) {
                    _dslDefinitions.add(Definition.DslDef(
                        irClass = targetClass,
                        definitionType = enclosingDefType,
                        bindings = emptyList(), // DSL: only explicit bind() adds bindings (no auto-bind like annotations)
                        scopeClass = if (enclosingDefType == DefinitionType.SCOPED) transformContext.scopeTypeClass else null,
                        modulePropertyId = transformContext.modulePropertyId,
                        qualifier = classQualifier
                    ))
                }
                val enclosingDef = currentDefinitionCall?.asString() ?: "unknown"
                KoinPluginLogger.user { "Intercepting $enclosingDef { create(::${targetClass.name}) } -> ${targetClass.name}" }
                builder.irCallConstructor(referencedFunction.symbol, emptyList()).apply {
                    referencedFunction.valueParameters.forEachIndexed { index, param ->
                        val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeReceiver, null, builder)
                        if (argument != null) {
                            putValueArgument(index, argument)
                        }
                        // If argument is null, parameter has a default value and will use it
                    }
                }
            }
            is IrSimpleFunction -> {
                // Extract qualifier from function for propagation to enclosing definition
                val returnTypeClass = referencedFunction.returnType.classifierOrNull?.owner as? IrClass
                val funcQualifier = qualifierExtractor.extractFromDeclaration(referencedFunction, "function ${referencedFunction.name}")
                if (funcQualifier != null && currentDefinitionCall != null) {
                    transformContext = transformContext.copy(
                        createQualifier = funcQualifier,
                        createReturnClass = returnTypeClass
                    )
                }
                val enclosingDefType = currentDefinitionCall?.let { definitionTypeMap[it] }
                if (enclosingDefType != null && compileSafetyEnabled && returnTypeClass != null) {
                    trackClassLookup(lookupTracker, currentFile, returnTypeClass)
                    _dslDefinitions.add(Definition.DslDef(
                        irClass = returnTypeClass,
                        definitionType = enclosingDefType,
                        bindings = emptyList(), // DSL: only explicit bind() adds bindings
                        scopeClass = if (enclosingDefType == DefinitionType.SCOPED) transformContext.scopeTypeClass else null,
                        modulePropertyId = transformContext.modulePropertyId,
                        providerOnly = true,
                        qualifier = funcQualifier
                    ))
                }
                val returnTypeName = referencedFunction.returnType.classFqName?.shortName() ?: referencedFunction.returnType.toString()
                val enclosingDef = currentDefinitionCall?.asString() ?: "unknown"
                KoinPluginLogger.user { "Intercepting $enclosingDef { create(::${referencedFunction.name}) } -> $returnTypeName" }
                builder.irCall(referencedFunction.symbol).apply {
                    referencedFunction.valueParameters.forEachIndexed { index, param ->
                        val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeReceiver, null, builder)
                        if (argument != null) {
                            putValueArgument(index, argument)
                        }
                        // If argument is null, parameter has a default value and will use it
                    }
                }
            }
        }
    }

    /**
     * Handle single/factory/etc { create(::ref) } where ::ref has a qualifier annotation.
     * Replaces the definition call with buildSingle/buildFactory/etc.(KClass, qualifier) { body }
     * so the qualifier is properly registered with the definition.
     */
    private fun handleDefinitionWithCreateQualifier(
        call: IrCall,
        receiver: IrExpression,
        receiverClassifier: IrClass,
        functionName: Name,
        returnClass: IrClass,
        qualifier: QualifierValue
    ): IrExpression {
        val receiverClassName = receiverClassifier.name.asString()
        val targetFunction = findTargetFunction(functionName, receiverClassName)
        if (targetFunction == null) {
            KoinPluginLogger.debug { "$functionName target function not found for $receiverClassName (qualifier propagation)" }
            return call
        }

        // Find the lambda argument from the original call
        val existingLambda = (0 until call.valueArgumentsCount)
            .mapNotNull { call.getValueArgument(it) }
            .firstOrNull { it is IrFunctionExpression }
            ?: return call

        KoinPluginLogger.user { "Applying qualifier ${qualifier.debugString()} to $functionName { create(::${returnClass.name}) }" }

        val builder = DeclarationIrBuilder(context, call.symbol, call.startOffset, call.endOffset)
        val erasedReturnType = erasedTypeForClass(returnClass)

        return builder.irCall(targetFunction.symbol).apply {
            this.extensionReceiver = receiver
            putTypeArgument(0, erasedReturnType)

            // Arg 0: KClass<T>
            val kClassClassOwner = kClassClass ?: return call
            putValueArgument(0, IrClassReferenceImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                kClassClassOwner.typeWith(erasedReturnType),
                returnClass.symbol,
                erasedReturnType
            ))

            // Arg 1: Qualifier
            putValueArgument(1, qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull())

            // Arg 2: Existing definition lambda (already transformed by super.visitCall)
            putValueArgument(2, existingLambda)
        }
    }

    /**
     * Validates that create() is the only instruction in the enclosing lambda.
     * Reports a compilation error if there are other statements.
     */
    private fun validateCreateInLambda(call: IrCall, referencedFunction: IrFunction) {
        val lambda = currentLambda ?: return  // Not inside a lambda, no validation needed

        val body = lambda.body as? IrBlockBody ?: return
        val statements = body.statements

        // A valid lambda body should have exactly one statement: a return with the create() call
        // or the create() call as an implicit return expression
        val isValid = when {
            statements.size == 1 -> {
                val stmt = statements[0]
                when (stmt) {
                    is IrReturn -> isCreateCall(stmt.value, call)
                    is IrCall -> isCreateCall(stmt, call)
                    else -> false
                }
            }
            else -> false
        }

        if (!isValid) {
            val targetName = when (referencedFunction) {
                is IrConstructor -> (referencedFunction.parent as IrClass).name.asString()
                is IrSimpleFunction -> referencedFunction.name.asString()
            }
            KoinPluginLogger.error(
                "create(::$targetName) must be the only instruction in the lambda. " +
                "Other statements are not allowed when using create(). " +
                "To disable this check, set koinCompiler { unsafeDslChecks = false } in your build.gradle.kts"
            )
        }
    }

    /**
     * Checks if the given expression is the create() call we're validating.
     */
    private fun isCreateCall(expr: IrExpression?, targetCall: IrCall): Boolean {
        return expr === targetCall
    }

    /**
     * Runtime KClass registration in Koin is erased, so generic type arguments are replaced
     * with Any? when emitting class literals (Foo::class).
     */
    private fun erasedTypeForClass(targetClass: IrClass): IrType {
        if (targetClass.typeParameters.isEmpty()) return targetClass.defaultType
        val erasedArguments = Array(targetClass.typeParameters.size) { context.irBuiltIns.anyNType }
        return targetClass.typeWith(*erasedArguments)
    }

    /**
     * Preserve concrete type arguments for constructor calls when the user wrote single<Foo<Bar>>().
     */
    private fun extractConstructorTypeArguments(typeArg: IrType, targetClass: IrClass): List<IrType> {
        val simpleType = typeArg as? IrSimpleType ?: return emptyList()
        if (simpleType.classifierOrNull?.owner != targetClass) return emptyList()
        if (simpleType.arguments.size != targetClass.typeParameters.size) return emptyList()

        val concreteArguments = simpleType.arguments
            .mapNotNull { (it as? IrTypeProjection)?.type }

        return if (concreteArguments.size == targetClass.typeParameters.size) {
            concreteArguments
        } else {
            emptyList()
        }
    }

    private fun findTargetFunction(functionName: Name, receiverClassName: String): IrSimpleFunction? {
        // Map stub function name to target function name (e.g., single -> buildSingle)
        val targetName = targetFunctionNames[functionName] ?: return null

        // Check cache first
        val cacheKey = functionName to receiverClassName
        targetFunctionCache[cacheKey]?.let { return it }

        val functions = context.referenceFunctions(
            CallableId(FqName("org.koin.plugin.module.dsl"), targetName)
        )
        val result = functions
            .map { it.owner }
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { function ->
                function.extensionReceiverParameter?.type?.classifierOrNull?.owner?.let {
                    (it as? IrClass)?.name?.asString() == receiverClassName
                } == true &&
                function.valueParameters.size >= 3 &&
                function.valueParameters[0].type.classifierOrNull?.owner?.let {
                    (it as? IrClass)?.name?.asString() == "KClass"
                } == true
            }

        // Cache the result (including null)
        targetFunctionCache[cacheKey] = result
        return result
    }

}

/**
 * A pending call-site validation collected during Phase 2.
 * Validated after Phase 3 when the assembled graph is available.
 */
data class PendingCallSiteValidation(
    val targetFqName: String,
    val targetClass: IrClass,
    val callFunctionName: String,
    val filePath: String?,
    val line: Int,
    val column: Int
)
