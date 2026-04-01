package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Transforms functions annotated with @Monitor by wrapping their bodies
 * with Kotzilla trace calls for performance monitoring.
 *
 * Supports:
 * - @Monitor on individual functions (including suspend functions)
 * - @Monitor on classes (applies to all public functions)
 *
 * Transformation example:
 * ```kotlin
 * @Monitor
 * fun myFunction(): String {
 *     return "hello"
 * }
 * ```
 * Becomes:
 * ```kotlin
 * fun myFunction(): String {
 *     return KotzillaCore.getDefaultInstance().trace("myFunction") {
 *         "hello"
 *     }
 * }
 * ```
 *
 * For suspend functions, uses `suspendTrace()` instead of `trace()`.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class KoinMonitorTransformer(
    private val context: IrPluginContext
) : IrElementTransformerVoid() {

    private val monitorFqName = KoinAnnotationFqNames.MONITOR

    // Cached lookups for Kotzilla SDK
    private val kotzillaCoreClass by lazy {
        context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KOTZILLA_CORE))?.owner
    }

    private val kotzillaCoreCompanion by lazy {
        kotzillaCoreClass?.declarations
            ?.filterIsInstance<IrClass>()
            ?.firstOrNull { it.isCompanion }
    }

    private val getDefaultInstanceFunction by lazy {
        kotzillaCoreCompanion?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.firstOrNull { it.name.asString() == "getDefaultInstance" }
    }

    private val traceFunction by lazy {
        kotzillaCoreClass?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.firstOrNull {
                it.name.asString() == "trace" &&
                !it.isSuspend &&
                it.typeParameters.size == 1
            }
    }

    private val suspendTraceFunction by lazy {
        kotzillaCoreClass?.declarations
            ?.filterIsInstance<IrSimpleFunction>()
            ?.firstOrNull {
                it.name.asString() == "suspendTrace" &&
                it.isSuspend &&
                it.typeParameters.size == 1
            }
    }

    // Function0 for regular lambdas: () -> T
    private val function0Class by lazy {
        context.referenceClass(ClassId.topLevel(FqName("kotlin.Function0")))?.owner
    }

    // SuspendFunction0 for suspend lambdas: suspend () -> T
    private val suspendFunction0Class by lazy {
        context.referenceClass(ClassId(FqName("kotlin.coroutines"), Name.identifier("SuspendFunction0")))?.owner
    }

    // Track transformed functions to avoid double-transformation
    private val transformedFunctions = mutableSetOf<IrSimpleFunction>()

    // Track monitored classes for summary logging: className -> function count
    private val monitoredClasses = mutableMapOf<String, Int>()

    // Track if we've already logged the SDK missing warning
    private var sdkMissingWarningLogged = false

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        // Skip if SDK functions not available
        if (kotzillaCoreClass == null || getDefaultInstanceFunction == null) {
            // Only log once per compilation to avoid spam
            if (!sdkMissingWarningLogged && shouldMonitorFunction(declaration)) {
                KoinPluginLogger.warn("@Monitor: Kotzilla SDK not found on classpath - monitoring disabled")
                sdkMissingWarningLogged = true
            }
            return super.visitSimpleFunction(declaration)
        }

        // Skip if already transformed
        if (declaration in transformedFunctions) {
            return super.visitSimpleFunction(declaration)
        }

        // Check if function should be monitored
        if (!shouldMonitorFunction(declaration)) {
            return super.visitSimpleFunction(declaration)
        }

        // Skip if no body
        val originalBody = declaration.body ?: return super.visitSimpleFunction(declaration)

        // Skip synthetic functions, constructors, accessors
        if (declaration.isFakeOverride || declaration.origin == IrDeclarationOrigin.GENERATED_SETTER_GETTER) {
            return super.visitSimpleFunction(declaration)
        }

        // Generate the label
        val label = generateLabel(declaration)

        KoinPluginLogger.user { "@Monitor: $label" }

        // Mark as transformed before wrapping
        transformedFunctions.add(declaration)

        // Wrap the body
        val wrappedBody = wrapBodyWithTrace(declaration, originalBody, label)
        if (wrappedBody != null) {
            declaration.body = wrappedBody

            // Track class-level monitoring for summary
            val parentClass = declaration.parent as? IrClass
            val className = parentClass?.name?.asString() ?: "<top-level>"
            monitoredClasses[className] = (monitoredClasses[className] ?: 0) + 1
        }

        return super.visitSimpleFunction(declaration)
    }

    /**
     * Log a summary of monitored classes. Always emitted so users know tracing is active.
     */
    fun logSummary() {
        for ((className, count) in monitoredClasses) {
            KoinPluginLogger.warn("@Monitor: $className - tracing enabled ($count functions)")
        }
    }

    private fun shouldMonitorFunction(function: IrSimpleFunction): Boolean {
        // Check for @Monitor on the function itself
        if (function.hasAnnotation(monitorFqName)) {
            return true
        }

        // Check for @Monitor on the containing class
        val parentClass = function.parent as? IrClass
        if (parentClass != null && parentClass.hasAnnotation(monitorFqName)) {
            // Only monitor public functions when class is annotated
            return function.visibility == DescriptorVisibilities.PUBLIC
        }

        return false
    }

    private fun IrDeclaration.hasAnnotation(fqName: FqName): Boolean {
        return annotations.any { annotation ->
            annotation.type.classFqName?.asString() == fqName.asString()
        }
    }

    private fun generateLabel(function: IrSimpleFunction): String {
        val parentClass = function.parent as? IrClass
        return if (parentClass != null && !parentClass.isCompanion) {
            "${parentClass.name.asString()}.${function.name.asString()}"
        } else {
            function.name.asString()
        }
    }

    private fun wrapBodyWithTrace(
        function: IrSimpleFunction,
        originalBody: IrBody,
        label: String
    ): IrBody? {
        val builder = DeclarationIrBuilder(context, function.symbol)
        val isSuspend = function.isSuspend

        // Choose the appropriate trace function
        val traceFn = if (isSuspend) suspendTraceFunction else traceFunction
        if (traceFn == null) {
            KoinPluginLogger.debug { "  trace function not found for ${if (isSuspend) "suspend" else "regular"} function" }
            return null
        }

        val getDefaultInstanceFn = getDefaultInstanceFunction ?: return null
        val companion = kotzillaCoreCompanion ?: return null

        // Create: KotzillaCore.getDefaultInstance()
        // Note: getDefaultInstance is a companion object function
        val getInstance = builder.irCall(getDefaultInstanceFn.symbol).apply {
            dispatchReceiver = builder.irGetObject(companion.symbol)
        }

        // Create lambda wrapping original body
        val lambdaExpr = createTraceLambda(function, originalBody, builder) ?: return null

        // Create: getInstance().trace("label", false) { originalBody }
        // or: getInstance().suspendTrace("label", false) { originalBody }
        val traceCall = builder.irCall(traceFn.symbol).apply {
            dispatchReceiver = getInstance
            putTypeArgument(0, function.returnType)
            putValueArgument(0, builder.irString(label))
            putValueArgument(1, builder.irFalse())  // stacktrace = false
            putValueArgument(2, lambdaExpr)
        }

        // Wrap in return statement
        return builder.irBlockBody {
            +irReturn(traceCall)
        }
    }

    private fun createTraceLambda(
        parentFunction: IrSimpleFunction,
        originalBody: IrBody,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        val isSuspend = parentFunction.isSuspend
        val func0Class = if (isSuspend) suspendFunction0Class else function0Class
        if (func0Class == null) {
            KoinPluginLogger.debug { "  Function0 class not found" }
            return null
        }

        // Create lambda function with same suspend status as parent
        val lambdaFunction = context.irFactory.createSimpleFunction(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
            name = Name.special("<anonymous>"),
            visibility = DescriptorVisibilities.LOCAL,
            isInline = false,
            isExpect = false,
            returnType = parentFunction.returnType,
            modality = Modality.FINAL,
            symbol = IrSimpleFunctionSymbolImpl(),
            isTailrec = false,
            isSuspend = isSuspend,  // Match parent's suspend status
            isOperator = false,
            isInfix = false,
            isExternal = false,
            containerSource = null,
            isFakeOverride = false
        )
        lambdaFunction.parent = parentFunction

        // Move original body to lambda, adjusting returns
        lambdaFunction.body = transformBodyForLambda(originalBody, parentFunction, lambdaFunction)

        // Create lambda type: () -> T or suspend () -> T
        val lambdaType = func0Class.typeWith(parentFunction.returnType)

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = lambdaType,
            origin = IrStatementOrigin.LAMBDA,
            function = lambdaFunction
        )
    }

    private fun transformBodyForLambda(
        originalBody: IrBody,
        originalFunction: IrSimpleFunction,
        lambdaFunction: IrSimpleFunction
    ): IrBody {
        // Transform returns from the original function to returns from the lambda
        return originalBody.transform(object : IrElementTransformerVoid() {
            override fun visitReturn(expression: IrReturn): IrExpression {
                val transformedValue = expression.value.transform(this, null)
                return if (expression.returnTargetSymbol == originalFunction.symbol) {
                    // Redirect return to lambda
                    IrReturnImpl(
                        expression.startOffset,
                        expression.endOffset,
                        expression.type,
                        lambdaFunction.symbol,
                        transformedValue
                    )
                } else {
                    super.visitReturn(expression)
                }
            }
        }, null) as IrBody
    }
}
