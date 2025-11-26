package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.PropertyValueRegistry

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
    private val context: IrPluginContext
) : IrElementTransformerVoid() {

    private val dslSafetyChecksEnabled = KoinPluginLogger.dslSafetyChecksEnabled

    // Qualifier extraction helper
    private val qualifierExtractor = QualifierExtractor(context)

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
    private val scopeClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.SCOPE_CLASS))?.owner }
    private val parametersHolderClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.PARAMETERS_HOLDER))?.owner }
    private val function2Class by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.FUNCTION2))?.owner }
    private val lazyModeClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.LAZY_THREAD_SAFETY_MODE))?.owner }

    // Cache for target functions (buildSingle, buildFactory, etc.)
    private val targetFunctionCache = mutableMapOf<Pair<Name, String>, IrSimpleFunction?>()

    /**
     * Context passed through the transformation to track the current position in the tree.
     * Using immutable data class with stack-based save/restore pattern for cleaner state management.
     *
     * @property function The enclosing function being visited
     * @property lambda The enclosing lambda (for create() validation)
     * @property definitionCall The enclosing DSL definition call (single/factory/scoped/etc.)
     */
    private data class TransformContext(
        val function: IrFunction? = null,
        val lambda: IrSimpleFunction? = null,
        val definitionCall: Name? = null
    )

    // Stack-based context management (thread-safe for single-threaded compiler)
    private var transformContext = TransformContext()

    // Convenience accessors for cleaner code
    private val currentFunction: IrFunction? get() = transformContext.function
    private val currentLambda: IrSimpleFunction? get() = transformContext.lambda
    private val currentDefinitionCall: Name? get() = transformContext.definitionCall

    override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
        val previousContext = transformContext
        transformContext = transformContext.copy(lambda = expression.function)
        val result = super.visitFunctionExpression(expression)
        transformContext = previousContext
        return result
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        val previousContext = transformContext
        transformContext = transformContext.copy(function = declaration)
        val result = super.visitFunction(declaration)
        transformContext = previousContext
        return result
    }

    // DSL definition function names to track
    private val definitionNames = setOf(singleName, factoryName, scopedName, viewModelName, workerName)

    override fun visitCall(expression: IrCall): IrExpression {
        val functionName = expression.symbol.owner.name

        // Track if we're entering a Koin DSL definition call (single, factory, scoped, etc.)
        val previousContext = transformContext
        if (functionName in definitionNames) {
            transformContext = transformContext.copy(definitionCall = functionName)
        }

        val transformedCall = super.visitCall(expression) as IrCall

        // Restore previous context
        transformContext = previousContext

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
        val constructor = targetClass.primaryConstructor ?: return call
        val receiverClassName = receiverClassifier.name.asString()

        // Log the interception
        KoinPluginLogger.user { "Intercepting $functionName<${targetClass.name}>() on $receiverClassName" }

        val builder = DeclarationIrBuilder(context, call.symbol, call.startOffset, call.endOffset)

        // Find target function with KClass parameter
        val targetFunction = findTargetFunction(functionName, receiverClassName) ?: return call

        // Get qualifier from @Named or @Qualifier annotation on class
        val qualifier = qualifierExtractor.extractFromClass(targetClass)

        // For worker definitions, use class name as qualifier (required by WorkManager)
        val effectiveQualifier: QualifierValue? = if (functionName == workerName) {
            QualifierValue.StringQualifier(targetClass.fqNameWhenAvailable?.asString() ?: targetClass.name.asString())
        } else {
            qualifier
        }

        // Build the transformed call
        return builder.irCall(targetFunction.symbol).apply {
            this.extensionReceiver = extensionReceiver
            putTypeArgument(0, targetClass.defaultType)

            // Arg 0: KClass<T>
            val kClassClassOwner = kClassClass ?: return call
            putValueArgument(0, IrClassReferenceImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                kClassClassOwner.typeWith(targetClass.defaultType),
                targetClass.symbol,
                targetClass.defaultType
            ))

            // Arg 1: Qualifier? (for workers, always use class name as qualifier)
            putValueArgument(1, qualifierExtractor.createQualifierCall(effectiveQualifier, builder) ?: builder.irNull())

            // Arg 2: Definition lambda { T(get(), get(), ...) }
            val parentFunc = currentFunction ?: return call
            putValueArgument(2, createDefinitionLambda(constructor, targetClass, builder, parentFunc))
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
        if (dslSafetyChecksEnabled) {
            validateCreateInLambda(call, referencedFunction)
        }

        val builder = DeclarationIrBuilder(context, call.symbol, call.startOffset, call.endOffset)

        return when (referencedFunction) {
            is IrConstructor -> {
                val targetClass = referencedFunction.parent as IrClass
                val enclosingDef = currentDefinitionCall?.asString() ?: "unknown"
                KoinPluginLogger.user { "Intercepting $enclosingDef { create(::${targetClass.name}) } -> ${targetClass.name}" }
                builder.irCallConstructor(referencedFunction.symbol, emptyList()).apply {
                    referencedFunction.valueParameters.forEachIndexed { index, param ->
                        putValueArgument(index, generateKoinArgumentForParameter(param, scopeReceiver, null, builder))
                    }
                }
            }
            is IrSimpleFunction -> {
                val returnTypeName = referencedFunction.returnType.classFqName?.shortName() ?: referencedFunction.returnType.toString()
                val enclosingDef = currentDefinitionCall?.asString() ?: "unknown"
                KoinPluginLogger.user { "Intercepting $enclosingDef { create(::${referencedFunction.name}) } -> $returnTypeName" }
                builder.irCall(referencedFunction.symbol).apply {
                    referencedFunction.valueParameters.forEachIndexed { index, param ->
                        putValueArgument(index, generateKoinArgumentForParameter(param, scopeReceiver, null, builder))
                    }
                }
            }
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
                "To disable this check, set koinCompiler { dslSafetyChecks = false } in your build.gradle.kts"
            )
        }
    }

    /**
     * Checks if the given expression is the create() call we're validating.
     */
    private fun isCreateCall(expr: IrExpression?, targetCall: IrCall): Boolean {
        return expr === targetCall
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

    private fun createDefinitionLambda(
        constructor: IrConstructor,
        returnTypeClass: IrClass,
        builder: DeclarationIrBuilder,
        parentFunction: IrFunction
    ): IrExpression {
        val scopeClassOwner = scopeClass ?: return builder.irNull()
        val parametersHolderClassOwner = parametersHolderClass ?: return builder.irNull()

        val lambdaFunction = context.irFactory.createSimpleFunction(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
            name = Name.special("<anonymous>"),
            visibility = DescriptorVisibilities.LOCAL,
            isInline = false,
            isExpect = false,
            returnType = returnTypeClass.defaultType,
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

        val extensionReceiverParam = context.irFactory.createValueParameter(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.special("<this>"),
            type = scopeClassOwner.defaultType,
            isAssignable = false,
            symbol = org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl(),
            index = -1,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false
        )
        extensionReceiverParam.parent = lambdaFunction
        lambdaFunction.extensionReceiverParameter = extensionReceiverParam

        val parametersHolderParam = context.irFactory.createValueParameter(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("params"),
            type = parametersHolderClassOwner.defaultType,
            isAssignable = false,
            symbol = org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl(),
            index = 0,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false
        )
        parametersHolderParam.parent = lambdaFunction
        lambdaFunction.valueParameters = listOf(parametersHolderParam)

        val lambdaBuilder = DeclarationIrBuilder(context, lambdaFunction.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)

        val constructorCall = lambdaBuilder.irCallConstructor(constructor.symbol, emptyList()).apply {
            constructor.valueParameters.forEachIndexed { index, param ->
                val scopeGet = lambdaBuilder.irGet(extensionReceiverParam)
                val paramsGet = lambdaBuilder.irGet(parametersHolderParam)
                putValueArgument(index, generateKoinArgumentForParameter(param, scopeGet, paramsGet, lambdaBuilder))
            }
        }

        lambdaFunction.body = context.irFactory.createBlockBody(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            listOf(lambdaBuilder.irReturn(constructorCall))
        )

        val function2ClassOwner = function2Class ?: return builder.irNull()

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = function2ClassOwner.typeWith(scopeClassOwner.defaultType, parametersHolderClassOwner.defaultType, returnTypeClass.defaultType),
            origin = IrStatementOrigin.LAMBDA,
            function = lambdaFunction
        )
    }

    // Qualifier extraction and creation methods have been moved to QualifierExtractor

    private fun generateKoinArgumentForParameter(
        param: IrValueParameter,
        scopeReceiver: IrExpression,
        parametersHolderReceiver: IrExpression?,
        builder: DeclarationIrBuilder
    ): IrExpression {
        val paramType = param.type

        // @Property -> getProperty()
        val propertyKey = qualifierExtractor.getPropertyAnnotationKey(param)
        if (propertyKey != null) {
            return createGetPropertyCall(scopeReceiver, propertyKey, paramType, builder)
        }

        // @InjectedParam -> ParametersHolder.get()
        if (qualifierExtractor.hasInjectedParamAnnotation(param) && parametersHolderReceiver != null) {
            return if (paramType.makeNotNull() != paramType) {
                createParametersHolderGetOrNullCall(parametersHolderReceiver, paramType.makeNotNull(), builder)
            } else {
                createParametersHolderGetCall(parametersHolderReceiver, paramType, builder)
            }
        }

        val qualifier = qualifierExtractor.extractFromParameter(param)
        val classifier = paramType.classifierOrNull?.owner

        // Lazy<T> -> inject()
        if (classifier is IrClass && classifier.name.asString() == "Lazy" && classifier.packageFqName?.asString() == "kotlin") {
            val typeArgument = (paramType as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
            if (typeArgument != null) {
                return createScopeInjectCall(scopeReceiver, typeArgument, qualifier, builder)
            }
        }

        // List<T> -> getAll()
        if (classifier is IrClass && classifier.name.asString() == "List" && classifier.packageFqName?.asString() == "kotlin.collections") {
            val typeArgument = (paramType as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
            if (typeArgument != null) {
                return createScopeGetAllCall(scopeReceiver, typeArgument, builder)
            }
        }

        // Nullable -> getOrNull()
        if (paramType.makeNotNull() != paramType) {
            return createScopeGetOrNullCall(scopeReceiver, paramType.makeNotNull(), qualifier, builder)
        }

        // Non-nullable -> get()
        return createScopeGetCall(scopeReceiver, paramType, qualifier, builder)
    }

    private fun createGetPropertyCall(scopeReceiver: IrExpression, propertyKey: String, expectedType: IrType, builder: DeclarationIrBuilder): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass) ?: return builder.irNull()

        // Check if there's a @PropertyValue default for this key
        val defaultProperty = PropertyValueRegistry.getDefault(propertyKey)

        if (defaultProperty != null) {
            // Use getProperty(key, defaultValue) with 2 parameters
            val getPropertyWithDefaultFunction = scopeClass.declarations
                .filterIsInstance<IrSimpleFunction>()
                .firstOrNull { it.name.asString() == "getProperty" && it.typeParameters.size == 1 && it.valueParameters.size == 2 }

            if (getPropertyWithDefaultFunction != null) {
                KoinPluginLogger.debug { "  Using getProperty(\"$propertyKey\", ${defaultProperty.name}) with @PropertyValue default" }
                return builder.irCall(getPropertyWithDefaultFunction.symbol).apply {
                    dispatchReceiver = scopeReceiver
                    putTypeArgument(0, expectedType)
                    putValueArgument(0, builder.irString(propertyKey))
                    // Reference the default property's getter
                    val getter = defaultProperty.getter
                    if (getter != null) {
                        putValueArgument(1, builder.irCall(getter.symbol))
                    } else {
                        // Fallback to backing field if no getter
                        val backingField = defaultProperty.backingField
                        if (backingField != null) {
                            putValueArgument(1, builder.irGetField(null, backingField))
                        }
                    }
                }
            }
        }

        // Use single parameter version: getProperty(key)
        val getPropertyFunction = scopeClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { it.name.asString() == "getProperty" && it.typeParameters.size == 1 && it.valueParameters.size == 1 }
        return if (getPropertyFunction != null) {
            builder.irCall(getPropertyFunction.symbol).apply {
                dispatchReceiver = scopeReceiver
                putTypeArgument(0, expectedType)
                putValueArgument(0, builder.irString(propertyKey))
            }
        } else {
            builder.irNull()
        }
    }

    private fun createScopeGetAllCall(scopeReceiver: IrExpression, elementType: IrType, builder: DeclarationIrBuilder): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass) ?: return builder.irNull()
        val getAllFunction = scopeClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { it.name.asString() == "getAll" && it.typeParameters.size == 1 }
        return if (getAllFunction != null) {
            builder.irCall(getAllFunction.symbol).apply {
                dispatchReceiver = scopeReceiver
                putTypeArgument(0, elementType)
            }
        } else {
            builder.irNull()
        }
    }

    private fun createScopeGetCall(scopeReceiver: IrExpression, type: IrType, qualifier: QualifierValue?, builder: DeclarationIrBuilder): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass) ?: return builder.irNull()
        val getFunction = scopeClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter { it.name.asString() == "get" && it.typeParameters.size == 1 && it.valueParameters.all { p -> p.type.makeNotNull() != p.type } }
            .minByOrNull { it.valueParameters.size }
            ?: return builder.irNull()

        return builder.irCall(getFunction.symbol).apply {
            dispatchReceiver = scopeReceiver
            putTypeArgument(0, type)
            getFunction.valueParameters.forEachIndexed { index, param ->
                val paramTypeName = (param.type.classifierOrNull?.owner as? IrClass)?.name?.asString()
                if (index == 0 && paramTypeName == "Qualifier" && qualifier != null) {
                    putValueArgument(index, qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull())
                } else {
                    putValueArgument(index, builder.irNull())
                }
            }
        }
    }

    private fun createScopeGetOrNullCall(scopeReceiver: IrExpression, type: IrType, qualifier: QualifierValue?, builder: DeclarationIrBuilder): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass) ?: return builder.irNull()
        val getOrNullFunction = scopeClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter { it.name.asString() == "getOrNull" && it.typeParameters.size == 1 && it.valueParameters.all { p -> p.type.makeNotNull() != p.type } }
            .minByOrNull { it.valueParameters.size }
            ?: return builder.irNull()

        return builder.irCall(getOrNullFunction.symbol).apply {
            dispatchReceiver = scopeReceiver
            putTypeArgument(0, type)
            getOrNullFunction.valueParameters.forEachIndexed { index, param ->
                val paramTypeName = (param.type.classifierOrNull?.owner as? IrClass)?.name?.asString()
                if (index == 0 && paramTypeName == "Qualifier" && qualifier != null) {
                    putValueArgument(index, qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull())
                } else {
                    putValueArgument(index, builder.irNull())
                }
            }
        }
    }

    private fun createScopeInjectCall(scopeReceiver: IrExpression, type: IrType, qualifier: QualifierValue?, builder: DeclarationIrBuilder): IrExpression {
        val scopeClassFromReceiver = (scopeReceiver.type.classifierOrNull?.owner as? IrClass) ?: return builder.irNull()
        val injectFunction = scopeClassFromReceiver.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter { it.name.asString() == "inject" && it.typeParameters.size == 1 }
            .minByOrNull { it.valueParameters.size }
            ?: return builder.irNull()

        val synchronizedEntry = lazyModeClass?.declarations?.filterIsInstance<IrEnumEntry>()?.firstOrNull { it.name.asString() == "SYNCHRONIZED" }

        return builder.irCall(injectFunction.symbol).apply {
            dispatchReceiver = scopeReceiver
            putTypeArgument(0, type)
            injectFunction.valueParameters.forEachIndexed { index, param ->
                val paramType = param.type
                val paramTypeName = (paramType.classifierOrNull?.owner as? IrClass)?.name?.asString()
                when {
                    paramTypeName == "Qualifier" && qualifier != null -> putValueArgument(index, qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull())
                    paramType.classifierOrNull?.owner == lazyModeClass && synchronizedEntry != null -> {
                        putValueArgument(index, IrGetEnumValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, paramType, synchronizedEntry.symbol))
                    }
                    paramType.makeNotNull() != paramType -> putValueArgument(index, builder.irNull())
                }
            }
        }
    }

    private fun createParametersHolderGetCall(parametersHolderReceiver: IrExpression, type: IrType, builder: DeclarationIrBuilder): IrExpression {
        val parametersHolderClassFromReceiver = (parametersHolderReceiver.type.classifierOrNull?.owner as? IrClass) ?: return builder.irNull()
        val getFunction = parametersHolderClassFromReceiver.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { it.name.asString() == "get" && it.typeParameters.size == 1 && it.valueParameters.isEmpty() }
            ?: return builder.irNull()

        return builder.irCall(getFunction.symbol).apply {
            dispatchReceiver = parametersHolderReceiver
            putTypeArgument(0, type)
        }
    }

    private fun createParametersHolderGetOrNullCall(parametersHolderReceiver: IrExpression, type: IrType, builder: DeclarationIrBuilder): IrExpression {
        val parametersHolderClass = (parametersHolderReceiver.type.classifierOrNull?.owner as? IrClass) ?: return builder.irNull()
        val getOrNullFunction = parametersHolderClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { it.name.asString() == "getOrNull" && it.typeParameters.size == 1 && it.valueParameters.isEmpty() }
            ?: return builder.irNull()

        return builder.irCall(getOrNullFunction.symbol).apply {
            dispatchReceiver = parametersHolderReceiver
            putTypeArgument(0, type)
        }
    }
}
