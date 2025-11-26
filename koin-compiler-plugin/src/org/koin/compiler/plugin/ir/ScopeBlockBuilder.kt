package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Builds Koin scope blocks for annotations processing.
 *
 * This class centralizes the boilerplate code for creating scope blocks:
 * ```kotlin
 * scope<ScopeClass> { scoped { ... } }
 * viewModelScope { viewModel { ... } }
 * activityScope { scoped { ... } }
 * ```
 *
 * The scope block lambdas have:
 * - Extension receiver: ScopeDSL (for scoped(), viewModel(), etc.)
 * - Return type: Unit
 *
 * Scope archetypes supported:
 * - viewModelScope (koin-core-viewmodel)
 * - activityScope, fragmentScope, activityRetainedScope (koin-android)
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class ScopeBlockBuilder(
    private val context: IrPluginContext
) {
    // Cached class lookups
    private val scopeDslClass by lazy {
        context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.SCOPE_DSL))?.owner
    }

    private val function1Class by lazy {
        context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.FUNCTION1))?.owner
    }

    private val kClassClass by lazy {
        context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KCLASS))?.owner
    }

    private val koinModuleFqName = KoinAnnotationFqNames.KOIN_MODULE

    /**
     * Build: scope<ScopeClass> { ... }
     *
     * Creates a scope block for a specific scope class. The block contains scoped definitions.
     *
     * @param scopeClass The class used as the scope identifier
     * @param moduleReceiver The Module receiver for the scope call
     * @param parentLambdaFunction The parent function containing this scope block
     * @param builder The declaration builder
     * @param statementsBuilder Callback to build the statements inside the scope block.
     *                          Receives the ScopeDSL receiver parameter and the scope lambda function.
     * @return The scope call expression, or null if required classes are not found
     */
    fun buildScopeBlock(
        scopeClass: IrClass,
        moduleReceiver: IrValueParameter,
        parentLambdaFunction: IrFunction,
        builder: DeclarationIrBuilder,
        statementsBuilder: (scopeDslReceiver: IrValueParameter, parentFunction: IrFunction, lambdaBuilder: DeclarationIrBuilder) -> List<IrStatement>
    ): IrExpression? {
        // Find the scope(qualifier, block) function from ModuleExt.kt
        val scopeDslFunction = findScopeFunction() ?: return null

        // Create the scope lambda and call
        val scopeLambdaResult = createScopeLambda(parentLambdaFunction, statementsBuilder)
            ?: return null

        // Create qualifier from scope class: typeQualifier<ScopeClass>()
        val qualifierCall = createTypeQualifier(scopeClass, builder) ?: return null

        return builder.irCall(scopeDslFunction.symbol).apply {
            extensionReceiver = builder.irGet(moduleReceiver)
            putValueArgument(0, qualifierCall)
            putValueArgument(1, scopeLambdaResult.expression)
        }
    }

    /**
     * Build archetype scope block: viewModelScope { }, activityScope { }, etc.
     *
     * @param archetype The scope archetype (VIEW_MODEL_SCOPE, ACTIVITY_SCOPE, etc.)
     * @param moduleReceiver The Module receiver for the scope call
     * @param parentLambdaFunction The parent function containing this scope block
     * @param builder The declaration builder
     * @param statementsBuilder Callback to build the statements inside the scope block
     * @return The archetype scope call expression, or null if required classes are not found
     */
    fun buildArchetypeScopeBlock(
        archetype: ScopeArchetype,
        moduleReceiver: IrValueParameter,
        parentLambdaFunction: IrFunction,
        builder: DeclarationIrBuilder,
        statementsBuilder: (scopeDslReceiver: IrValueParameter, parentFunction: IrFunction, lambdaBuilder: DeclarationIrBuilder) -> List<IrStatement>
    ): IrExpression? {
        // Find the archetype scope function (e.g., viewModelScope, activityScope)
        val scopeFunction = findArchetypeScopeFunction(archetype)
        if (scopeFunction == null) {
            KoinPluginLogger.debug { "No matching archetype scope function found: ${archetype.scopeFunctionName} in ${archetype.packageName}" }
            return null
        }

        // Create the scope lambda
        val scopeLambdaResult = createScopeLambda(parentLambdaFunction, statementsBuilder)
            ?: return null

        // Call the archetype scope function (e.g., viewModelScope { })
        return builder.irCall(scopeFunction.symbol).apply {
            extensionReceiver = builder.irGet(moduleReceiver)
            putValueArgument(0, scopeLambdaResult.expression)
        }
    }

    /**
     * Create a type-based qualifier: typeQualifier(ScopeClass::class)
     */
    fun createTypeQualifier(typeClass: IrClass, builder: DeclarationIrBuilder): IrExpression? {
        // Find typeQualifier(KClass) function from ModuleExt.kt
        val typeQualifierFunction = context.referenceFunctions(
            CallableId(FqName("org.koin.plugin.module.dsl"), Name.identifier("typeQualifier"))
        ).firstOrNull()?.owner ?: return null

        val kClass = kClassClass ?: return null

        return builder.irCall(typeQualifierFunction.symbol).apply {
            putTypeArgument(0, typeClass.defaultType)

            // Create KClass reference: ScopeClass::class
            val kClassType = kClass.typeWith(typeClass.defaultType)
            val classReference = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = typeClass.symbol,
                classType = typeClass.defaultType
            )
            putValueArgument(0, classReference)
        }
    }

    /**
     * Result of creating a scope lambda.
     */
    private data class ScopeLambdaResult(
        val expression: IrExpression,
        val function: IrSimpleFunction,
        val receiverParam: IrValueParameter
    )

    /**
     * Create a scope lambda: ScopeDSL.() -> Unit
     */
    private fun createScopeLambda(
        parentLambdaFunction: IrFunction,
        statementsBuilder: (scopeDslReceiver: IrValueParameter, parentFunction: IrFunction, lambdaBuilder: DeclarationIrBuilder) -> List<IrStatement>
    ): ScopeLambdaResult? {
        val scopeDsl = scopeDslClass ?: return null
        val func1Class = function1Class ?: return null

        // Create the scope lambda function: ScopeDSL.() -> Unit
        val scopeLambdaFunction = context.irFactory.createSimpleFunction(
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
        scopeLambdaFunction.parent = parentLambdaFunction

        // Create extension receiver parameter (ScopeDSL)
        val scopeDslReceiverParam = context.irFactory.createValueParameter(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.special("<this>"),
            type = scopeDsl.defaultType,
            isAssignable = false,
            symbol = IrValueParameterSymbolImpl(),
            index = -1,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false
        )
        scopeDslReceiverParam.parent = scopeLambdaFunction
        scopeLambdaFunction.extensionReceiverParameter = scopeDslReceiverParam

        // Build the statements using the callback
        val scopeLambdaBuilder = DeclarationIrBuilder(context, scopeLambdaFunction.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
        val statements = statementsBuilder(scopeDslReceiverParam, scopeLambdaFunction, scopeLambdaBuilder)

        scopeLambdaFunction.body = context.irFactory.createBlockBody(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET
        ).apply {
            this.statements.addAll(statements)
        }

        // Create the lambda type: Function1<ScopeDSL, Unit>
        val scopeLambdaType = func1Class.typeWith(scopeDsl.defaultType, context.irBuiltIns.unitType)

        val scopeLambdaExpr = IrFunctionExpressionImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = scopeLambdaType,
            origin = IrStatementOrigin.LAMBDA,
            function = scopeLambdaFunction
        )

        return ScopeLambdaResult(scopeLambdaExpr, scopeLambdaFunction, scopeDslReceiverParam)
    }

    /**
     * Find the scope(qualifier, block) function from ModuleExt.kt
     */
    private fun findScopeFunction(): IrSimpleFunction? {
        val allScopeFunctions = context.referenceFunctions(
            CallableId(FqName("org.koin.plugin.module.dsl"), Name.identifier("scope"))
        )

        return allScopeFunctions.firstOrNull { func ->
            // Look for: Module.scope(qualifier: Qualifier, scopeSet: ScopeDSL.() -> Unit)
            func.owner.typeParameters.isEmpty() &&
            func.owner.extensionReceiverParameter?.type?.classFqName?.asString() == koinModuleFqName.asString() &&
            func.owner.valueParameters.size == 2 &&
            func.owner.valueParameters[0].name.asString() == "qualifier"
        }?.owner
    }

    /**
     * Find an archetype scope function (viewModelScope, activityScope, etc.)
     */
    private fun findArchetypeScopeFunction(archetype: ScopeArchetype): IrSimpleFunction? {
        // Filter for the Module extension function that takes a lambda parameter,
        // not the KoinScopeComponent extension that returns a Scope directly
        return context.referenceFunctions(
            CallableId(FqName(archetype.packageName), Name.identifier(archetype.scopeFunctionName))
        ).firstOrNull { it.owner.valueParameters.isNotEmpty() }?.owner
    }
}

/**
 * Supported scope archetypes with their corresponding function names and packages.
 */
enum class ScopeArchetype(val scopeFunctionName: String, val packageName: String) {
    VIEW_MODEL_SCOPE("viewModelScope", "org.koin.viewmodel.scope"),
    ACTIVITY_SCOPE("activityScope", "org.koin.androidx.scope.dsl"),
    ACTIVITY_RETAINED_SCOPE("activityRetainedScope", "org.koin.androidx.scope.dsl"),
    FRAGMENT_SCOPE("fragmentScope", "org.koin.androidx.scope.dsl")
}
