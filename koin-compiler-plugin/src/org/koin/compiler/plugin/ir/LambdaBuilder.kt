package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Builds Koin definition lambda expressions for constructor, function, and top-level function definitions.
 *
 * This class centralizes the boilerplate code for creating lambda expressions:
 * ```kotlin
 * { scope: Scope, params: ParametersHolder -> ... }
 * ```
 *
 * The lambda always has:
 * - Extension receiver: Scope (for scope.get(), scope.inject(), etc.)
 * - Value parameter: ParametersHolder (for @InjectedParam)
 * - Return type: The definition's return type
 *
 * Usage:
 * ```kotlin
 * val lambda = lambdaBuilder.createForConstructor(constructor, returnTypeClass, builder, parentFunction)
 * val lambda = lambdaBuilder.createForFunction(function, returnTypeClass, moduleClass, builder, parentFunction, getterFunction)
 * val lambda = lambdaBuilder.createForTopLevelFunction(function, returnTypeClass, builder, parentFunction)
 * ```
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class LambdaBuilder(
    private val context: IrPluginContext,
    private val qualifierExtractor: QualifierExtractor,
    private val argumentGenerator: ArgumentGenerator
) {

    // Cached class lookups
    private val scopeClass by lazy {
        context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.SCOPE_CLASS))?.owner
    }

    private val parametersHolderClass by lazy {
        context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.PARAMETERS_HOLDER))?.owner
    }

    private val function2Class by lazy {
        context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.FUNCTION2))?.owner
    }

    /**
     * Create a definition lambda that creates the body using the provided callback.
     *
     * The callback receives:
     * - lambdaBuilder: DeclarationIrBuilder for building the body
     * - scopeParam: The Scope extension receiver parameter
     * - paramsParam: The ParametersHolder value parameter
     *
     * @param returnType The return type of the lambda
     * @param builder The outer declaration builder
     * @param parentFunction The parent function containing this lambda
     * @param bodyBuilder Callback to create the body expression
     * @return The lambda expression, or irNull() if required classes are not found
     */
    fun create(
        returnType: IrType,
        builder: DeclarationIrBuilder,
        parentFunction: IrFunction,
        bodyBuilder: (
            lambdaBuilder: DeclarationIrBuilder,
            scopeParam: IrValueParameter,
            paramsParam: IrValueParameter
        ) -> IrExpression
    ): IrExpression {
        val scopeClassLocal = scopeClass
        val paramsHolderClass = parametersHolderClass
        val func2Class = function2Class
        if (scopeClassLocal == null || paramsHolderClass == null || func2Class == null) {
            KoinPluginLogger.debug { "Koin core classes not found on classpath (Scope=${scopeClassLocal != null}, ParametersHolder=${paramsHolderClass != null}, Function2=${func2Class != null})" }
            return builder.irNull()
        }

        // Create the lambda function
        val lambdaFunction = context.irFactory.createSimpleFunction(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
            name = Name.special("<anonymous>"),
            visibility = DescriptorVisibilities.LOCAL,
            isInline = false,
            isExpect = false,
            returnType = returnType,
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

        // Create extension receiver parameter (Scope)
        val extensionReceiverParam = context.irFactory.createValueParameter(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.special("<this>"),
            type = scopeClassLocal.defaultType,
            isAssignable = false,
            symbol = IrValueParameterSymbolImpl(),
            index = -1,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false
        )
        extensionReceiverParam.parent = lambdaFunction
        lambdaFunction.extensionReceiverParameter = extensionReceiverParam

        // Create ParametersHolder value parameter
        val parametersHolderParam = context.irFactory.createValueParameter(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("params"),
            type = paramsHolderClass.defaultType,
            isAssignable = false,
            symbol = IrValueParameterSymbolImpl(),
            index = 0,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false
        )
        parametersHolderParam.parent = lambdaFunction
        lambdaFunction.valueParameters = listOf(parametersHolderParam)

        // Create the body using the callback
        val lambdaBuilder = DeclarationIrBuilder(context, lambdaFunction.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
        val bodyExpression = bodyBuilder(lambdaBuilder, extensionReceiverParam, parametersHolderParam)

        lambdaFunction.body = context.irFactory.createBlockBody(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET
        ).apply {
            statements.add(lambdaBuilder.irReturn(bodyExpression))
        }

        // Create the lambda type: Function2<Scope, ParametersHolder, ReturnType>
        val lambdaType = func2Class.typeWith(
            scopeClassLocal.defaultType,
            paramsHolderClass.defaultType,
            returnType
        )

        return IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = lambdaType,
            origin = IrStatementOrigin.LAMBDA,
            function = lambdaFunction
        )
    }

    /**
     * Generate a Koin argument for a parameter (get(), getOrNull(), inject(), parametersOf(), getProperty()).
     *
     * Returns null if the parameter has a default value and no explicit annotation,
     * in which case the default value should be used.
     *
     * Delegates to the ArgumentGenerator for the actual implementation.
     */
    fun generateArgumentForParameter(
        param: IrValueParameter,
        scopeReceiver: IrExpression,
        parametersHolderReceiver: IrExpression?,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        return argumentGenerator.generateForParameter(param, scopeReceiver, parametersHolderReceiver, builder)
    }
}

/**
 * Interface for generating Koin arguments for constructor/function parameters.
 *
 * This abstraction allows the LambdaBuilder to be independent of the specific
 * argument generation logic in KoinAnnotationProcessor.
 */
interface ArgumentGenerator {
    /**
     * Generate the appropriate Koin call for a parameter.
     *
     * Returns null if the parameter has a default value and no explicit annotation,
     * in which case the default value should be used (no argument passed).
     *
     * Handles:
     * - @Property -> getProperty()
     * - @InjectedParam -> ParametersHolder.get()
     * - @Named/@Qualifier -> get(qualifier)
     * - List<T> -> getAll()
     * - Lazy<T> -> inject()
     * - Nullable -> getOrNull()
     * - Default value (no annotation) -> null (use Kotlin default)
     * - Default -> get()
     */
    fun generateForParameter(
        param: IrValueParameter,
        scopeReceiver: IrExpression,
        parametersHolderReceiver: IrExpression?,
        builder: DeclarationIrBuilder
    ): IrExpression?
}
