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
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger

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
            putValueArgument(2, lambdaBuilder.create(targetClass, builder, parentFunc) { lb, scopeParam, paramsParam ->
                lb.irCallConstructor(constructor.symbol, emptyList()).apply {
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
                        val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeReceiver, null, builder)
                        if (argument != null) {
                            putValueArgument(index, argument)
                        }
                        // If argument is null, parameter has a default value and will use it
                    }
                }
            }
            is IrSimpleFunction -> {
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

}
