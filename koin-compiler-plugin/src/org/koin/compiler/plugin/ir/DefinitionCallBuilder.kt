package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Builds IR call expressions for Koin definition DSL functions.
 *
 * Responsible for creating:
 * - Root-scope definitions: single(...) { T(get()) }, factory(...) { T(get()) }, etc.
 * - Scoped definitions: scoped(...) { T(get()) } inside scope<X> { }
 * - Top-level function definitions
 * - Binding chains: .bind(Interface::class)
 * - Include calls: includes(OtherModule().module())
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class DefinitionCallBuilder(
    private val context: IrPluginContext,
    private val qualifierExtractor: QualifierExtractor,
    private val lambdaBuilder: LambdaBuilder,
    private val argumentGenerator: KoinArgumentGenerator
) {

    private val jakartaInjectFqName = KoinAnnotationFqNames.JAKARTA_INJECT
    private val javaxInjectFqName = KoinAnnotationFqNames.JAVAX_INJECT

    private val kClassClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KCLASS))?.owner }

    /**
     * Build: single(A::class, null) { A(get(), get()) } [bind Interface::class]
     * With createdAtStart: single(A::class, null, createdAtStart = true) { A(get()) }
     */
    fun buildClassDefinitionCall(
        definition: Definition.ClassDef,
        moduleReceiver: IrValueParameter,
        parentFunction: IrFunction,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        val targetClass = definition.irClass
        val constructor = findConstructorToUse(targetClass)
        if (constructor == null) {
            KoinPluginLogger.debug { "No constructor found for ${targetClass.fqNameWhenAvailable} - definition skipped" }
            return null
        }

        val functionName = when (definition.definitionType) {
            DefinitionType.SINGLE -> "buildSingle"
            DefinitionType.FACTORY -> "buildFactory"
            DefinitionType.SCOPED -> "buildScoped"
            DefinitionType.VIEW_MODEL -> "buildViewModel"
            DefinitionType.WORKER -> "buildWorker"
        }

        val targetFunction = findDefinitionWithKClass(Name.identifier(functionName), "org.koin.plugin.module.dsl", "Module")
        if (targetFunction == null) {
            KoinPluginLogger.debug { "$functionName not found for Module receiver - definition ${targetClass.name} skipped" }
            return null
        }

        val kClass = kClassClass ?: return null

        val classQualifier = qualifierExtractor.extractFromClass(targetClass)

        // For worker definitions, use class FQN as qualifier (required by WorkManager)
        val effectiveQualifier: QualifierValue? = if (definition.definitionType == DefinitionType.WORKER) {
            QualifierValue.StringQualifier(targetClass.fqNameWhenAvailable?.asString() ?: targetClass.name.asString())
        } else {
            classQualifier
        }

        val definitionCall = builder.irCall(targetFunction.symbol).apply {
            extensionReceiver = builder.irGet(moduleReceiver)
            putTypeArgument(0, targetClass.defaultType)

            val kClassType = kClass.typeWith(targetClass.defaultType)
            val classReference = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = targetClass.symbol,
                classType = targetClass.defaultType
            )
            putValueArgument(0, classReference)

            val qualifierArg: IrExpression = qualifierExtractor.createQualifierCall(effectiveQualifier, builder) ?: builder.irNull()
            putValueArgument(1, qualifierArg)

            val definitionLambda = createDefinitionLambda(constructor, targetClass, builder, parentFunction)
            putValueArgument(2, definitionLambda)

            // Add createdAtStart parameter if applicable (only for SINGLE)
            if (definition.createdAtStart && definition.definitionType == DefinitionType.SINGLE) {
                // Find the createdAtStart parameter index (usually 3 or via name)
                val createdAtStartIndex = targetFunction.valueParameters.indexOfFirst {
                    it.name.asString() == "createdAtStart"
                }
                if (createdAtStartIndex >= 0) {
                    putValueArgument(createdAtStartIndex, builder.irTrue())
                }
            }
        }

        // Add bindings if any
        if (definition.bindings.isNotEmpty()) {
            return addBindings(definitionCall, definition.bindings, builder)
        }

        return definitionCall
    }

    /**
     * Find the constructor to use for injection.
     * Prefers @Inject annotated constructor (JSR-330), otherwise uses primary constructor.
     */
    private fun findConstructorToUse(targetClass: IrClass): IrConstructor? {
        // Look for @Inject annotated constructor (JSR-330 - jakarta.inject or javax.inject)
        val injectConstructor = targetClass.declarations
            .filterIsInstance<IrConstructor>()
            .firstOrNull { constructor ->
                constructor.annotations.any { annotation ->
                    val fqName = annotation.type.classFqName?.asString()
                    fqName == jakartaInjectFqName.asString() || fqName == javaxInjectFqName.asString()
                }
            }

        return injectConstructor ?: targetClass.primaryConstructor
    }

    /**
     * Build: single(FunBuilder::class, null) { moduleInstance.myFunBuilder(get(), getOrNull()) }
     */
    fun buildFunctionDefinitionCall(
        definition: Definition.FunctionDef,
        moduleClass: ModuleClass,
        moduleReceiver: IrValueParameter,
        parentFunction: IrFunction,
        builder: DeclarationIrBuilder,
        getterFunction: IrFunction
    ): IrExpression? {
        val targetFunction = definition.irFunction
        val returnTypeClass = definition.returnTypeClass

        val functionName = when (definition.definitionType) {
            DefinitionType.SINGLE -> "buildSingle"
            DefinitionType.FACTORY -> "buildFactory"
            DefinitionType.SCOPED -> "buildScoped"
            DefinitionType.VIEW_MODEL -> "buildViewModel"
            DefinitionType.WORKER -> "buildWorker"
        }

        val koinFunction = findDefinitionWithKClass(Name.identifier(functionName), "org.koin.plugin.module.dsl", "Module")
        if (koinFunction == null) {
            KoinPluginLogger.debug { "$functionName not found for Module receiver - function ${targetFunction.name} skipped" }
            return null
        }

        val kClass = kClassClass ?: return null

        val qualifier = qualifierExtractor.extractFromDeclaration(targetFunction)

        return builder.irCall(koinFunction.symbol).apply {
            extensionReceiver = builder.irGet(moduleReceiver)
            putTypeArgument(0, returnTypeClass.defaultType)

            val kClassType = kClass.typeWith(returnTypeClass.defaultType)
            val classReference = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = returnTypeClass.symbol,
                classType = returnTypeClass.defaultType
            )
            putValueArgument(0, classReference)

            val qualifierArg: IrExpression = qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull()
            putValueArgument(1, qualifierArg)

            val definitionLambda = createFunctionDefinitionLambda(
                targetFunction, returnTypeClass, moduleClass, builder, parentFunction, getterFunction
            )
            putValueArgument(2, definitionLambda)
        }
    }

    /**
     * Build: single(FunBuilder::class, null) { topLevelFunction(get(), getOrNull()) }
     * For top-level functions (no module instance receiver)
     */
    fun buildTopLevelFunctionDefinitionCall(
        definition: Definition.TopLevelFunctionDef,
        moduleReceiver: IrValueParameter,
        parentFunction: IrFunction,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        val targetFunction = definition.irFunction
        val returnTypeClass = definition.returnTypeClass

        val functionName = when (definition.definitionType) {
            DefinitionType.SINGLE -> "buildSingle"
            DefinitionType.FACTORY -> "buildFactory"
            DefinitionType.SCOPED -> "buildScoped"
            DefinitionType.VIEW_MODEL -> "buildViewModel"
            DefinitionType.WORKER -> "buildWorker"
        }

        val koinFunction = findDefinitionWithKClass(Name.identifier(functionName), "org.koin.plugin.module.dsl", "Module")
        if (koinFunction == null) {
            KoinPluginLogger.debug { "$functionName not found for Module receiver - top-level function ${targetFunction.name} skipped" }
            return null
        }

        val kClass = kClassClass ?: return null

        val qualifier = qualifierExtractor.extractFromDeclaration(targetFunction)

        val definitionCall = builder.irCall(koinFunction.symbol).apply {
            extensionReceiver = builder.irGet(moduleReceiver)
            putTypeArgument(0, returnTypeClass.defaultType)

            val kClassType = kClass.typeWith(returnTypeClass.defaultType)
            val classReference = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = returnTypeClass.symbol,
                classType = returnTypeClass.defaultType
            )
            putValueArgument(0, classReference)

            val qualifierArg: IrExpression = qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull()
            putValueArgument(1, qualifierArg)

            val definitionLambda = createTopLevelFunctionDefinitionLambda(
                targetFunction, returnTypeClass, builder, parentFunction
            )
            putValueArgument(2, definitionLambda)

            // Add createdAtStart parameter if applicable (only for SINGLE)
            if (definition.createdAtStart && definition.definitionType == DefinitionType.SINGLE) {
                val createdAtStartIndex = koinFunction.valueParameters.indexOfFirst {
                    it.name.asString() == "createdAtStart"
                }
                if (createdAtStartIndex >= 0) {
                    putValueArgument(createdAtStartIndex, builder.irTrue())
                }
            }
        }

        // Add bindings if present
        return if (definition.bindings.isNotEmpty()) {
            addBindings(definitionCall, definition.bindings, builder)
        } else {
            definitionCall
        }
    }

    /**
     * Build: scoped(T::class, null) { T(...) } for use inside scope<X> { }
     */
    fun buildScopedClassDefinitionCall(
        definition: Definition.ClassDef,
        scopeDslReceiver: IrValueParameter,
        parentFunction: IrFunction,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        val targetClass = definition.irClass
        val constructor = findConstructorToUse(targetClass)
        if (constructor == null) {
            KoinPluginLogger.debug { "No constructor found for scoped ${targetClass.fqNameWhenAvailable} - definition skipped" }
            return null
        }

        val functionName = when (definition.definitionType) {
            DefinitionType.SINGLE -> return null  // Singles can't be inside scope blocks
            DefinitionType.FACTORY -> "buildFactory"
            DefinitionType.SCOPED -> "buildScoped"
            DefinitionType.VIEW_MODEL -> "buildViewModel"
            DefinitionType.WORKER -> "buildWorker"
        }

        val scopedFunction = findDefinitionWithKClass(Name.identifier(functionName), "org.koin.plugin.module.dsl", "ScopeDSL")
        if (scopedFunction == null) {
            KoinPluginLogger.debug { "$functionName not found for ScopeDSL receiver - scoped ${targetClass.name} skipped" }
            return null
        }

        val kClass = kClassClass ?: return null

        val classQualifier = qualifierExtractor.extractFromClass(targetClass)

        // For worker definitions, use class FQN as qualifier (required by WorkManager)
        val effectiveQualifier: QualifierValue? = if (definition.definitionType == DefinitionType.WORKER) {
            QualifierValue.StringQualifier(targetClass.fqNameWhenAvailable?.asString() ?: targetClass.name.asString())
        } else {
            classQualifier
        }

        val definitionCall = builder.irCall(scopedFunction.symbol).apply {
            extensionReceiver = builder.irGet(scopeDslReceiver)

            val kClassType = kClass.typeWith(targetClass.defaultType)
            val classReference = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = targetClass.symbol,
                classType = targetClass.defaultType
            )
            putValueArgument(0, classReference)

            val qualifierCall = qualifierExtractor.createQualifierCall(effectiveQualifier, builder)
            putValueArgument(1, qualifierCall ?: builder.irNull())

            val definitionLambda = createDefinitionLambda(constructor, targetClass, builder, parentFunction)
            putValueArgument(2, definitionLambda)
        }

        return addBindings(definitionCall, definition.bindings, builder)
    }

    /**
     * Build: scoped(T::class, null) { moduleInstance.functionCall(...) } for use inside scope<X> { }
     */
    fun buildScopedFunctionDefinitionCall(
        definition: Definition.FunctionDef,
        moduleClass: ModuleClass,
        scopeDslReceiver: IrValueParameter,
        parentFunction: IrFunction,
        builder: DeclarationIrBuilder,
        getterFunction: IrFunction
    ): IrExpression? {
        val targetFunction = definition.irFunction
        val returnTypeClass = definition.returnTypeClass

        val functionName = when (definition.definitionType) {
            DefinitionType.SINGLE -> return null  // Singles can't be inside scope blocks
            DefinitionType.FACTORY -> "buildFactory"
            DefinitionType.SCOPED -> "buildScoped"
            DefinitionType.VIEW_MODEL -> "buildViewModel"
            DefinitionType.WORKER -> "buildWorker"
        }

        val scopedFunction = findDefinitionWithKClass(Name.identifier(functionName), "org.koin.plugin.module.dsl", "ScopeDSL")
        if (scopedFunction == null) {
            KoinPluginLogger.debug { "$functionName not found for ScopeDSL receiver - scoped function ${targetFunction.name} skipped" }
            return null
        }

        val kClass = kClassClass ?: return null

        val qualifier = qualifierExtractor.extractFromDeclaration(targetFunction)

        val definitionCall = builder.irCall(scopedFunction.symbol).apply {
            extensionReceiver = builder.irGet(scopeDslReceiver)
            putTypeArgument(0, returnTypeClass.defaultType)

            val kClassType = kClass.typeWith(returnTypeClass.defaultType)
            val classReference = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = returnTypeClass.symbol,
                classType = returnTypeClass.defaultType
            )
            putValueArgument(0, classReference)

            val qualifierArg: IrExpression = qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull()
            putValueArgument(1, qualifierArg)

            val definitionLambda = createFunctionDefinitionLambda(
                targetFunction, returnTypeClass, moduleClass, builder, parentFunction, getterFunction
            )
            putValueArgument(2, definitionLambda)
        }

        return addBindings(definitionCall, definition.bindings, builder)
    }

    /**
     * Build: scoped(T::class, null) { topLevelFunction(...) } for use inside scope<X> { }
     */
    fun buildScopedTopLevelFunctionDefinitionCall(
        definition: Definition.TopLevelFunctionDef,
        scopeDslReceiver: IrValueParameter,
        parentFunction: IrFunction,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        val targetFunction = definition.irFunction
        val returnTypeClass = definition.returnTypeClass

        val functionName = when (definition.definitionType) {
            DefinitionType.SINGLE -> return null  // Singles can't be inside scope blocks
            DefinitionType.FACTORY -> "buildFactory"
            DefinitionType.SCOPED -> "buildScoped"
            DefinitionType.VIEW_MODEL -> "buildViewModel"
            DefinitionType.WORKER -> "buildWorker"
        }

        val scopedFunction = findDefinitionWithKClass(Name.identifier(functionName), "org.koin.plugin.module.dsl", "ScopeDSL")
        if (scopedFunction == null) {
            KoinPluginLogger.debug { "$functionName not found for ScopeDSL receiver - scoped top-level function ${targetFunction.name} skipped" }
            return null
        }

        val kClass = kClassClass ?: return null

        val qualifier = qualifierExtractor.extractFromDeclaration(targetFunction)

        val definitionCall = builder.irCall(scopedFunction.symbol).apply {
            extensionReceiver = builder.irGet(scopeDslReceiver)
            putTypeArgument(0, returnTypeClass.defaultType)

            val kClassType = kClass.typeWith(returnTypeClass.defaultType)
            val classReference = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = returnTypeClass.symbol,
                classType = returnTypeClass.defaultType
            )
            putValueArgument(0, classReference)

            val qualifierArg: IrExpression = qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull()
            putValueArgument(1, qualifierArg)

            val definitionLambda = createTopLevelFunctionDefinitionLambda(
                targetFunction, returnTypeClass, builder, parentFunction
            )
            putValueArgument(2, definitionLambda)
        }

        return addBindings(definitionCall, definition.bindings, builder)
    }

    /**
     * Add .bind(Interface::class) calls for auto-binding
     */
    fun addBindings(
        definitionCall: IrExpression,
        bindings: List<IrClass>,
        builder: DeclarationIrBuilder
    ): IrExpression {
        var result = definitionCall

        val bindFunction = context.referenceFunctions(
            CallableId(FqName("org.koin.plugin.module.dsl"), Name.identifier("bind"))
        ).firstOrNull()?.owner

        val kClass = kClassClass ?: return result

        if (bindFunction != null) {
            for (binding in bindings) {
                result = builder.irCall(bindFunction.symbol).apply {
                    extensionReceiver = result
                    putTypeArgument(0, binding.defaultType)

                    val kClassType = kClass.typeWith(binding.defaultType)
                    val classReference = IrClassReferenceImpl(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        type = kClassType,
                        symbol = binding.symbol,
                        classType = binding.defaultType
                    )
                    putValueArgument(0, classReference)
                }
            }
        }

        return result
    }

    fun findDefinitionWithKClass(functionName: Name, packageName: String, receiverClassName: String): IrSimpleFunction? {
        val functions = context.referenceFunctions(
            CallableId(FqName(packageName), functionName)
        )

        return functions
            .map { it.owner }
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { function ->
                val receiverClass = function.extensionReceiverParameter?.type?.classifierOrNull?.owner as? IrClass
                val firstParamClass = function.valueParameters.getOrNull(0)?.type?.classifierOrNull?.owner as? IrClass
                val secondParamClass = function.valueParameters.getOrNull(1)?.type?.classifierOrNull?.owner as? IrClass

                receiverClass?.name?.asString() == receiverClassName &&
                function.valueParameters.size >= 3 &&
                firstParamClass?.name?.asString() == "KClass" &&
                secondParamClass?.name?.asString() == "Qualifier"
            }
    }

    /**
     * Create a lambda expression for constructor: { Constructor(get(), get(), ...) }
     */
    private fun createDefinitionLambda(
        constructor: IrConstructor,
        returnTypeClass: IrClass,
        builder: DeclarationIrBuilder,
        parentFunction: IrFunction
    ): IrExpression {
        return lambdaBuilder.create(returnTypeClass, builder, parentFunction) { irBuilder, scopeParam, paramsParam ->
            irBuilder.irCallConstructor(constructor.symbol, emptyList()).apply {
                constructor.valueParameters.forEachIndexed { index, param ->
                    val scopeGet = irBuilder.irGet(scopeParam)
                    val paramsGet = irBuilder.irGet(paramsParam)
                    val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeGet, paramsGet, irBuilder)
                    if (argument != null) {
                        putValueArgument(index, argument)
                    }
                }
            }
        }
    }

    /**
     * Create a lambda expression for function definition: { moduleInstance.functionName(get(), ...) }
     */
    private fun createFunctionDefinitionLambda(
        targetFunction: IrSimpleFunction,
        returnTypeClass: IrClass,
        moduleClass: ModuleClass,
        builder: DeclarationIrBuilder,
        parentFunction: IrFunction,
        getterFunction: IrFunction
    ): IrExpression {
        val moduleInstanceReceiver = getterFunction.extensionReceiverParameter
        if (moduleInstanceReceiver == null) {
            KoinPluginLogger.debug { "No extension receiver on getter for function ${targetFunction.name} - lambda skipped" }
            return builder.irNull()
        }

        return lambdaBuilder.create(returnTypeClass, builder, parentFunction) { irBuilder, scopeParam, paramsParam ->
            irBuilder.irCall(targetFunction.symbol).apply {
                dispatchReceiver = irBuilder.irGet(moduleInstanceReceiver)

                targetFunction.valueParameters.forEachIndexed { index, param ->
                    val scopeGet = irBuilder.irGet(scopeParam)
                    val paramsGet = irBuilder.irGet(paramsParam)
                    val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeGet, paramsGet, irBuilder)
                    if (argument != null) {
                        putValueArgument(index, argument)
                    }
                }
            }
        }
    }

    /**
     * Create a lambda expression for top-level function definition: { topLevelFunction(get(), ...) }
     */
    private fun createTopLevelFunctionDefinitionLambda(
        targetFunction: IrSimpleFunction,
        returnTypeClass: IrClass,
        builder: DeclarationIrBuilder,
        parentFunction: IrFunction
    ): IrExpression {
        return lambdaBuilder.create(returnTypeClass, builder, parentFunction) { irBuilder, scopeParam, paramsParam ->
            irBuilder.irCall(targetFunction.symbol).apply {
                targetFunction.valueParameters.forEachIndexed { index, param ->
                    val scopeGet = irBuilder.irGet(scopeParam)
                    val paramsGet = irBuilder.irGet(paramsParam)
                    val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeGet, paramsGet, irBuilder)
                    if (argument != null) {
                        putValueArgument(index, argument)
                    }
                }
            }
        }
    }
}
