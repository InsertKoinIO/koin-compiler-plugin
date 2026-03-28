package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.PropertyValueRegistry

/**
 * Generates Koin injection arguments for constructor/function parameters.
 *
 * Handles all parameter resolution strategies:
 * - @Property -> getProperty()
 * - @InjectedParam -> ParametersHolder.get()
 * - @Named/@Qualifier -> get(qualifier)
 * - Lazy<T> -> inject()
 * - List<T> -> getAll()
 * - Nullable T? -> getOrNull()
 * - Regular T -> get()
 * - Skip default values (when enabled)
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class KoinArgumentGenerator(
    private val context: IrPluginContext,
    private val qualifierExtractor: QualifierExtractor
) : ArgumentGenerator {

    private val propertyAnnotationFqName = KoinAnnotationFqNames.PROPERTY
    private val lazyModeClass by lazy { context.referenceClass(ClassId.topLevel(FqName("kotlin.LazyThreadSafetyMode"))) }
    private val listClass by lazy { context.referenceClass(ClassId.topLevel(FqName("kotlin.collections.List")))?.owner }
    private val lazyClass by lazy { context.referenceClass(ClassId.topLevel(FqName("kotlin.Lazy")))?.owner }

    override fun generateForParameter(
        param: IrValueParameter,
        scopeReceiver: IrExpression,
        parametersHolderReceiver: IrExpression?,
        builder: DeclarationIrBuilder
    ): IrExpression? = generateKoinArgumentForParameter(param, scopeReceiver, parametersHolderReceiver, builder)

    /**
     * Generates a Koin argument for a constructor/function parameter.
     * Returns null if the parameter has a default value and no explicit annotation,
     * in which case the default value should be used.
     */
    fun generateKoinArgumentForParameter(
        param: IrValueParameter,
        scopeReceiver: IrExpression,
        parametersHolderReceiver: IrExpression?,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        val paramType = param.type

        // Check for @Property annotation - use getProperty() instead of get()
        val propertyKey = getPropertyAnnotationKey(param)
        if (propertyKey != null) {
            return if (paramType.isMarkedNullable()) {
                createGetPropertyOrNullCall(scopeReceiver, propertyKey, builder)
            } else {
                createGetPropertyCall(scopeReceiver, propertyKey, builder)
            }
        }

        // Check for @InjectedParam - use ParametersHolder.get()
        if (qualifierExtractor.hasInjectedParamAnnotation(param) && parametersHolderReceiver != null) {
            if (paramType.isMarkedNullable()) {
                val nonNullType = paramType.makeNotNull()
                return createParametersHolderGetOrNullCall(parametersHolderReceiver, nonNullType, builder)
            }
            return createParametersHolderGetCall(parametersHolderReceiver, paramType, builder)
        }

        val qualifier = qualifierExtractor.extractFromParameter(param)

        // If skipDefaultValues is enabled, parameter has a default value, is NOT nullable,
        // and has no explicit qualifier annotation, skip injection.
        // Nullable parameters should still use getOrNull() to let the DI container handle resolution.
        if (KoinPluginLogger.skipDefaultValuesEnabled && param.defaultValue != null && qualifier == null && !paramType.isMarkedNullable()) {
            KoinPluginLogger.user { "  Skipping injection for parameter '${param.name}' - using default value" }
            return null
        }

        val classifier = paramType.classifierOrNull?.owner
        if (classifier is IrClass) {
            val className = classifier.name.asString()
            val packageName = classifier.packageFqName?.asString()

            // Handle Lazy<T> -> inject()
            if (className == "Lazy" && packageName == "kotlin") {
                val lazyType = paramType as? IrSimpleType
                val typeArgument = lazyType?.arguments?.firstOrNull()?.typeOrNull
                if (typeArgument != null) {
                    return createScopeInjectCall(scopeReceiver, typeArgument, qualifier, builder)
                }
            }

            // Handle List<T> -> getAll()
            if (className == "List" && packageName == "kotlin.collections") {
                val listType = paramType as? IrSimpleType
                val typeArgument = listType?.arguments?.firstOrNull()?.typeOrNull
                if (typeArgument != null) {
                    return createScopeGetAllCall(scopeReceiver, typeArgument, builder)
                }
            }
        }

        if (paramType.isMarkedNullable()) {
            val nonNullType = paramType.makeNotNull()
            return createScopeGetOrNullCall(scopeReceiver, nonNullType, qualifier, builder)
        }

        return createScopeGetCall(scopeReceiver, paramType, qualifier, builder)
    }

    /**
     * Get property key from @Property annotation
     */
    private fun getPropertyAnnotationKey(param: IrValueParameter): String? {
        val propertyAnnotation = param.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == propertyAnnotationFqName.asString()
        } ?: return null

        val valueArg = propertyAnnotation.getValueArgument(0)
        if (valueArg is IrConst) {
            return valueArg.value as? String
        }
        return null
    }

    /**
     * Create scope.getProperty("key") or scope.getProperty("key", defaultValue) call
     */
    fun createGetPropertyCall(
        scopeReceiver: IrExpression,
        propertyKey: String,
        builder: DeclarationIrBuilder
    ): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass)
        if (scopeClass == null) {
            KoinPluginLogger.debug { "Could not resolve scope class for getProperty(\"$propertyKey\") call" }
            return builder.irNull()
        }

        // Check if there's a @PropertyValue default for this key
        val defaultProperty = PropertyValueRegistry.getDefault(propertyKey)

        if (defaultProperty != null) {
            // Use getProperty(key, defaultValue) with 2 parameters
            val getPropertyWithDefaultFunction = scopeClass.declarations
                .filterIsInstance<IrSimpleFunction>()
                .firstOrNull { function ->
                    function.name.asString() == "getProperty" &&
                    function.typeParameters.size == 1 &&
                    function.valueParameters.size == 2 &&
                    function.valueParameters[0].type.classifierOrNull?.owner?.let {
                        (it as? IrClass)?.name?.asString() == "String"
                    } == true
                }

            if (getPropertyWithDefaultFunction != null) {
                KoinPluginLogger.debug { "  Using getProperty(\"$propertyKey\", ${defaultProperty.name}) with @PropertyValue default" }
                return builder.irCall(getPropertyWithDefaultFunction.symbol).apply {
                    dispatchReceiver = scopeReceiver
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

        // Find getProperty function: getProperty(key: String): T (single parameter version)
        val getPropertyFunction = scopeClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { function ->
                function.name.asString() == "getProperty" &&
                function.typeParameters.size == 1 &&
                function.valueParameters.size == 1 &&
                function.valueParameters[0].type.classifierOrNull?.owner?.let {
                    (it as? IrClass)?.name?.asString() == "String"
                } == true
            }

        if (getPropertyFunction != null) {
            return builder.irCall(getPropertyFunction.symbol).apply {
                dispatchReceiver = scopeReceiver
                putValueArgument(0, builder.irString(propertyKey))
            }
        }

        // Fallback: try to find it from Koin extension
        val koinPropertyFunction = context.referenceFunctions(
            CallableId(FqName("org.koin.core.scope"), Name.identifier("getProperty"))
        ).firstOrNull()?.owner

        if (koinPropertyFunction != null) {
            return builder.irCall(koinPropertyFunction.symbol).apply {
                if (koinPropertyFunction.dispatchReceiverParameter != null) {
                    dispatchReceiver = scopeReceiver
                } else if (koinPropertyFunction.extensionReceiverParameter != null) {
                    extensionReceiver = scopeReceiver
                }
                putValueArgument(0, builder.irString(propertyKey))
            }
        }

        KoinPluginLogger.debug { "Could not find getProperty function for key \"$propertyKey\" on scope class ${scopeClass.name}" }
        return builder.irNull()
    }

    /**
     * Create scope.getPropertyOrNull("key") call for nullable properties.
     * Uses getPropertyOrNull() which returns null instead of throwing when the property is missing.
     */
    private fun createGetPropertyOrNullCall(
        scopeReceiver: IrExpression,
        propertyKey: String,
        builder: DeclarationIrBuilder
    ): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass)
        if (scopeClass == null) {
            KoinPluginLogger.debug { "Could not resolve scope class for getPropertyOrNull(\"$propertyKey\") call" }
            return builder.irNull()
        }

        // Find getPropertyOrNull function: getPropertyOrNull(key: String): T?
        val getPropertyOrNullFunction = scopeClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { function ->
                function.name.asString() == "getPropertyOrNull" &&
                function.typeParameters.size == 1 &&
                function.valueParameters.size == 1 &&
                function.valueParameters[0].type.classifierOrNull?.owner?.let {
                    (it as? IrClass)?.name?.asString() == "String"
                } == true
            }

        if (getPropertyOrNullFunction != null) {
            return builder.irCall(getPropertyOrNullFunction.symbol).apply {
                dispatchReceiver = scopeReceiver
                putValueArgument(0, builder.irString(propertyKey))
            }
        }

        // Fallback: try extension function from Koin
        val koinPropertyOrNullFunction = context.referenceFunctions(
            CallableId(FqName("org.koin.core.scope"), Name.identifier("getPropertyOrNull"))
        ).firstOrNull()?.owner

        if (koinPropertyOrNullFunction != null) {
            return builder.irCall(koinPropertyOrNullFunction.symbol).apply {
                if (koinPropertyOrNullFunction.dispatchReceiverParameter != null) {
                    dispatchReceiver = scopeReceiver
                } else if (koinPropertyOrNullFunction.extensionReceiverParameter != null) {
                    extensionReceiver = scopeReceiver
                }
                putValueArgument(0, builder.irString(propertyKey))
            }
        }

        KoinPluginLogger.debug { "Could not find getPropertyOrNull function for key \"$propertyKey\" on scope class ${scopeClass.name}" }
        return builder.irNull()
    }

    /**
     * Create scope.getAll<T>() call for List<T> dependencies
     */
    fun createScopeGetAllCall(
        scopeReceiver: IrExpression,
        elementType: IrType,
        builder: DeclarationIrBuilder
    ): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass)
        val listType = listClass?.typeWith(elementType)
        if (scopeClass != null) {
            // Prefer the member function when available, but fall back to the top-level extension
            // so List<T> injection keeps working across Koin API shapes.
            val getAllFunction = scopeClass.declarations
                .filterIsInstance<IrSimpleFunction>()
                .firstOrNull { function ->
                    function.name.asString() == "getAll" &&
                    function.typeParameters.size == 1 &&
                    function.valueParameters.isEmpty()
                }
                ?: context.referenceFunctions(
                    CallableId(FqName("org.koin.core.scope"), Name.identifier("getAll"))
                ).map { it.owner }
                    .filterIsInstance<IrSimpleFunction>()
                    .firstOrNull { function ->
                        function.name.asString() == "getAll" &&
                        function.typeParameters.size == 1 &&
                        function.valueParameters.isEmpty() &&
                        (
                            function.dispatchReceiverParameter?.type?.classifierOrNull?.owner == scopeClass ||
                                function.extensionReceiverParameter?.type?.classifierOrNull?.owner == scopeClass
                            )
                    }

            if (getAllFunction != null) {
                return builder.irCall(getAllFunction.symbol).apply {
                    // Explicitly actualize return type to avoid leaking unbound function type parameter.
                    if (listType != null) type = listType
                    if (getAllFunction.dispatchReceiverParameter != null) {
                        dispatchReceiver = scopeReceiver
                    } else if (getAllFunction.extensionReceiverParameter != null) {
                        extensionReceiver = scopeReceiver
                    }
                    putTypeArgument(0, elementType)
                }
            }

            KoinPluginLogger.debug {
                "Could not find getAll function on scope class ${scopeClass.name} for element type ${elementType.classFqName}; using emptyList() fallback"
            }
        } else {
            KoinPluginLogger.debug {
                "Could not resolve scope class for getAll<${elementType.classFqName}>() call; using emptyList() fallback"
            }
        }

        val emptyListFunction = context.referenceFunctions(
            CallableId(FqName("kotlin.collections"), Name.identifier("emptyList"))
        ).firstOrNull()?.owner

        if (emptyListFunction != null) {
            return builder.irCall(emptyListFunction.symbol).apply {
                if (listType != null) type = listType
                putTypeArgument(0, elementType)
            }
        }

        KoinPluginLogger.debug {
            "Could not resolve emptyList<${elementType.classFqName}>() fallback for List injection"
        }
        return builder.irNull()
    }

    fun createScopeGetCall(
        scopeReceiver: IrExpression,
        type: IrType,
        qualifier: QualifierValue?,
        builder: DeclarationIrBuilder
    ): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass)
        if (scopeClass == null) {
            KoinPluginLogger.debug { "Could not resolve scope class for get<${type.classFqName}>() call" }
            return builder.irNull()
        }

        val getFunction = scopeClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter { function ->
                function.name.asString() == "get" &&
                function.typeParameters.size == 1 &&
                function.valueParameters.all { it.type.isMarkedNullable() }
            }
            .minByOrNull { it.valueParameters.size }
        if (getFunction == null) {
            KoinPluginLogger.debug { "Could not find get() function on scope class ${scopeClass.name} for type ${type.classFqName}" }
            return builder.irNull()
        }

        val requestedType = type
        return builder.irCall(getFunction.symbol).apply {
            // Explicitly actualize return type to avoid leaking unbound function type parameter.
            this.type = requestedType
            dispatchReceiver = scopeReceiver
            putTypeArgument(0, requestedType)

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

    fun createScopeGetOrNullCall(
        scopeReceiver: IrExpression,
        type: IrType,
        qualifier: QualifierValue?,
        builder: DeclarationIrBuilder
    ): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass)
        if (scopeClass == null) {
            KoinPluginLogger.debug { "Could not resolve scope class for getOrNull<${type.classFqName}>() call" }
            return builder.irNull()
        }

        val getOrNullFunction = scopeClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter { function ->
                function.name.asString() == "getOrNull" &&
                function.typeParameters.size == 1 &&
                function.valueParameters.all { it.type.isMarkedNullable() }
            }
            .minByOrNull { it.valueParameters.size }
        if (getOrNullFunction == null) {
            KoinPluginLogger.debug { "Could not find getOrNull() function on scope class ${scopeClass.name} for type ${type.classFqName}" }
            return builder.irNull()
        }

        val requestedType = type
        return builder.irCall(getOrNullFunction.symbol).apply {
            // Explicitly actualize return type to avoid leaking unbound function type parameter.
            this.type = requestedType.makeNullable()
            dispatchReceiver = scopeReceiver
            putTypeArgument(0, requestedType)

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

    fun createScopeInjectCall(
        scopeReceiver: IrExpression,
        type: IrType,
        qualifier: QualifierValue?,
        builder: DeclarationIrBuilder
    ): IrExpression {
        val scopeClass = (scopeReceiver.type.classifierOrNull?.owner as? IrClass)
        if (scopeClass == null) {
            KoinPluginLogger.debug { "Could not resolve scope class for inject<${type.classFqName}>() call" }
            return builder.irNull()
        }

        val injectFunction = scopeClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter { function ->
                function.name.asString() == "inject" &&
                function.typeParameters.size == 1
            }
            .minByOrNull { it.valueParameters.size }
        if (injectFunction == null) {
            KoinPluginLogger.debug { "Could not find inject() function on scope class ${scopeClass.name} for type ${type.classFqName}" }
            return builder.irNull()
        }

        val lazyMode = lazyModeClass?.owner
        val synchronizedEntry = lazyMode?.declarations
            ?.filterIsInstance<IrEnumEntry>()
            ?.firstOrNull { it.name.asString() == "SYNCHRONIZED" }

        val requestedType = type
        return builder.irCall(injectFunction.symbol).apply {
            // Explicitly actualize return type to avoid leaking unbound function type parameter.
            val lazyType = lazyClass?.typeWith(requestedType)
            if (lazyType != null) {
                this.type = lazyType
            }
            dispatchReceiver = scopeReceiver
            putTypeArgument(0, requestedType)

            injectFunction.valueParameters.forEachIndexed { index, param ->
                val paramType = param.type
                val paramTypeName = (paramType.classifierOrNull?.owner as? IrClass)?.name?.asString()
                when {
                    paramTypeName == "Qualifier" && qualifier != null -> {
                        putValueArgument(index, qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull())
                    }
                    paramType.classifierOrNull?.owner == lazyMode && synchronizedEntry != null -> {
                        putValueArgument(index, IrGetEnumValueImpl(
                            UNDEFINED_OFFSET,
                            UNDEFINED_OFFSET,
                            paramType,
                            synchronizedEntry.symbol
                        ))
                    }
                    paramType.isMarkedNullable() -> {
                        putValueArgument(index, builder.irNull())
                    }
                }
            }
        }
    }

    fun createParametersHolderGetCall(
        parametersHolderReceiver: IrExpression,
        type: IrType,
        builder: DeclarationIrBuilder
    ): IrExpression {
        val parametersHolderClass = (parametersHolderReceiver.type.classifierOrNull?.owner as? IrClass)
        if (parametersHolderClass == null) {
            KoinPluginLogger.debug { "Could not resolve ParametersHolder class for get<${type.classFqName}>() call" }
            return builder.irNull()
        }

        val getFunction = parametersHolderClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { function ->
                function.name.asString() == "get" &&
                function.typeParameters.size == 1 &&
                function.valueParameters.isEmpty()
            }
        if (getFunction == null) {
            KoinPluginLogger.debug { "Could not find get() function on ParametersHolder class for type ${type.classFqName}" }
            return builder.irNull()
        }

        val requestedType = type
        return builder.irCall(getFunction.symbol).apply {
            this.type = requestedType
            dispatchReceiver = parametersHolderReceiver
            putTypeArgument(0, requestedType)
        }
    }

    fun createParametersHolderGetOrNullCall(
        parametersHolderReceiver: IrExpression,
        type: IrType,
        builder: DeclarationIrBuilder
    ): IrExpression {
        val parametersHolderClass = (parametersHolderReceiver.type.classifierOrNull?.owner as? IrClass)
        if (parametersHolderClass == null) {
            KoinPluginLogger.debug { "Could not resolve ParametersHolder class for getOrNull<${type.classFqName}>() call" }
            return builder.irNull()
        }

        val getOrNullFunction = parametersHolderClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { function ->
                function.name.asString() == "getOrNull" &&
                function.typeParameters.size == 1 &&
                function.valueParameters.isEmpty()
            }
        if (getOrNullFunction == null) {
            KoinPluginLogger.debug { "Could not find getOrNull() function on ParametersHolder class for type ${type.classFqName}" }
            return builder.irNull()
        }

        val requestedType = type
        return builder.irCall(getOrNullFunction.symbol).apply {
            this.type = requestedType.makeNullable()
            dispatchReceiver = parametersHolderReceiver
            putTypeArgument(0, requestedType)
        }
    }
}
