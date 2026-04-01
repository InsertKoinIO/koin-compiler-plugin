package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.FqName
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginConstants
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Represents a qualifier value - either a string name or a type reference.
 *
 * Used throughout the plugin to represent:
 * - @Named("qualifier-name") -> StringQualifier
 * - @Qualifier(SomeType::class) -> TypeQualifier
 * - @Qualifier(name = "string") -> StringQualifier
 * - Custom qualifiers (annotations annotated with @Qualifier) -> StringQualifier
 */
sealed class QualifierValue {
    data class StringQualifier(val name: String) : QualifierValue()
    data class TypeQualifier(val irClass: IrClass) : QualifierValue()

    fun debugString(): String = when (this) {
        is StringQualifier -> "@Named(\"$name\")"
        is TypeQualifier -> "@Qualifier(${irClass.name}::class)"
    }
}

/**
 * Extracts qualifier annotations from IR declarations.
 *
 * Handles:
 * - @Named("string") - Koin, jakarta.inject, javax.inject
 * - @Qualifier(SomeType::class) - Koin type-based qualifier
 * - @Qualifier(name = "string") - Koin string-based qualifier
 * - Custom qualifiers (annotations annotated with @Qualifier or @Named)
 *
 * This class centralizes qualifier extraction logic previously duplicated in
 * KoinDSLTransformer and KoinAnnotationProcessor.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class QualifierExtractor(private val context: IrPluginContext) {

    // Cached function lookups for creating qualifier calls
    private val namedFunctionSymbol by lazy {
        context.referenceFunctions(CallableId(KoinAnnotationFqNames.QUALIFIER_PACKAGE, Name.identifier("named")))
            .firstOrNull { it.owner.valueParameters.size == 1 && it.owner.valueParameters[0].type.isString() }
    }

    private val typeQualifierFunctionSymbol by lazy {
        context.referenceFunctions(CallableId(KoinAnnotationFqNames.PLUGIN_MODULE_DSL, Name.identifier("typeQualifier")))
            .firstOrNull()
    }

    private val kClassClass by lazy {
        context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KCLASS))?.owner
    }

    /**
     * Extract qualifier from an IR declaration (class, function, property, etc.).
     * Supports @Named, @Qualifier (type and string), and custom qualifiers.
     *
     * @param declaration The IR declaration to extract qualifier from
     * @param logContext Optional context string for debug logging (e.g., "class MyClass")
     * @return The qualifier value, or null if no qualifier annotation is present
     */
    fun extractFromDeclaration(declaration: IrDeclaration, logContext: String? = null): QualifierValue? {
        // Check for @Named (Koin, jakarta.inject, or javax.inject)
        val namedAnnotation = declaration.annotations.firstOrNull { annotation ->
            val fqName = annotation.type.classFqName?.asString()
            fqName == KoinAnnotationFqNames.NAMED.asString() ||
            fqName == KoinAnnotationFqNames.JAKARTA_NAMED.asString() ||
            fqName == KoinAnnotationFqNames.JAVAX_NAMED.asString()
        }

        if (namedAnnotation != null) {
            val valueArg = namedAnnotation.getValueArgument(0)
            if (valueArg is IrConst) {
                val value = valueArg.value as? String
                if (value != null) {
                    logContext?.let { KoinPluginLogger.debug { "  @Named(\"$value\") on $it" } }
                    return QualifierValue.StringQualifier(value)
                }
            }
        }

        // Check for @Qualifier(SomeType::class) or @Qualifier(name = "...") (Koin)
        val qualifierAnnotation = declaration.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.QUALIFIER.asString()
        }

        if (qualifierAnnotation != null) {
            // Check for type-based qualifier: @Qualifier(SomeType::class)
            val valueArg = qualifierAnnotation.getValueArgument(Name.identifier("value"))
                ?: qualifierAnnotation.getValueArgument(0)
            if (valueArg is IrClassReference) {
                val qualifierClass = valueArg.classType.classifierOrNull?.owner as? IrClass
                if (qualifierClass != null && qualifierClass.fqNameWhenAvailable?.asString() != "kotlin.Unit") {
                    logContext?.let { KoinPluginLogger.debug { "  @Qualifier(${qualifierClass.name}::class) on $it" } }
                    return QualifierValue.TypeQualifier(qualifierClass)
                }
            }
            // Check for string-based qualifier: @Qualifier(name = "string")
            val nameArg = qualifierAnnotation.getValueArgument(Name.identifier("name"))
            if (nameArg is IrConst) {
                val value = nameArg.value as? String
                if (!value.isNullOrEmpty()) {
                    logContext?.let { KoinPluginLogger.debug { "  @Qualifier(name = \"$value\") on $it" } }
                    return QualifierValue.StringQualifier(value)
                }
            }
        }

        // Check for custom qualifier annotations (annotations annotated with @Qualifier or @Named)
        return findCustomQualifierAnnotation(declaration.annotations, logContext)
    }

    /**
     * Extract qualifier from a class.
     * Convenience method that extracts qualifier with appropriate logging context.
     *
     * @param irClass The class to extract qualifier from
     * @return The qualifier value, or null if no qualifier annotation is present
     */
    fun extractFromClass(irClass: IrClass): QualifierValue? {
        return extractFromDeclaration(irClass, "class ${irClass.name}")
    }

    /**
     * Extract qualifier from a value parameter.
     * Supports the same qualifier annotations as declarations, plus jakarta/javax @Named.
     *
     * @param param The parameter to extract qualifier from
     * @return The qualifier value, or null if no qualifier annotation is present
     */
    fun extractFromParameter(param: IrValueParameter): QualifierValue? {
        // Check for @Named (Koin, jakarta.inject, or javax.inject)
        val namedAnnotation = param.annotations.firstOrNull { annotation ->
            val fqName = annotation.type.classFqName?.asString()
            fqName == KoinAnnotationFqNames.NAMED.asString() ||
            fqName == KoinAnnotationFqNames.JAKARTA_NAMED.asString() ||
            fqName == KoinAnnotationFqNames.JAVAX_NAMED.asString()
        }

        if (namedAnnotation != null) {
            val valueArg = namedAnnotation.getValueArgument(0)
            if (valueArg is IrConst) {
                val value = valueArg.value as? String
                if (value != null) {
                    KoinPluginLogger.debug { "  @Named(\"$value\") on parameter ${param.name}" }
                    return QualifierValue.StringQualifier(value)
                }
            }
        }

        // Check for @Qualifier(SomeType::class) or @Qualifier(name = "...") (Koin)
        val qualifierAnnotation = param.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.QUALIFIER.asString()
        }

        if (qualifierAnnotation != null) {
            // Check for type-based qualifier: @Qualifier(SomeType::class)
            val valueArg = qualifierAnnotation.getValueArgument(Name.identifier("value"))
                ?: qualifierAnnotation.getValueArgument(0)
            if (valueArg is IrClassReference) {
                val qualifierClass = valueArg.classType.classifierOrNull?.owner as? IrClass
                if (qualifierClass != null && qualifierClass.fqNameWhenAvailable?.asString() != "kotlin.Unit") {
                    KoinPluginLogger.debug { "  @Qualifier(${qualifierClass.name}::class) on parameter ${param.name}" }
                    return QualifierValue.TypeQualifier(qualifierClass)
                }
            }
            // Check for string-based qualifier: @Qualifier(name = "string")
            val nameArg = qualifierAnnotation.getValueArgument(Name.identifier("name"))
            if (nameArg is IrConst) {
                val value = nameArg.value as? String
                if (!value.isNullOrEmpty()) {
                    KoinPluginLogger.debug { "  @Qualifier(name = \"$value\") on parameter ${param.name}" }
                    return QualifierValue.StringQualifier(value)
                }
            }
        }

        // Check for custom qualifier annotations
        return findCustomQualifierAnnotation(param.annotations, "parameter ${param.name}")
    }

    /**
     * Check if a meta-annotation matches a known qualifier FQ name.
     * Uses symbol-based resolution (constructor parent class) which is more reliable
     * than type-based resolution for cross-module deserialized annotation classes.
     */
    private fun isQualifierMetaAnnotation(metaAnnotation: IrConstructorCall): Boolean {
        // Primary: check via type (works for same-module classes)
        val metaFqName = metaAnnotation.type.classFqName?.asString()
        if (metaFqName != null) {
            return metaFqName == KoinAnnotationFqNames.QUALIFIER.asString() ||
                metaFqName == KoinAnnotationFqNames.NAMED.asString() ||
                metaFqName == KoinAnnotationFqNames.JAKARTA_QUALIFIER.asString() ||
                metaFqName == KoinAnnotationFqNames.JAVAX_QUALIFIER.asString()
        }
        // Fallback: check via constructor symbol's parent class (works for cross-module deserialized classes)
        val parentFqName = (metaAnnotation.symbol.owner.parent as? IrClass)?.fqNameWhenAvailable?.asString()
        return parentFqName == KoinAnnotationFqNames.QUALIFIER.asString() ||
            parentFqName == KoinAnnotationFqNames.NAMED.asString() ||
            parentFqName == KoinAnnotationFqNames.JAKARTA_QUALIFIER.asString() ||
            parentFqName == KoinAnnotationFqNames.JAVAX_QUALIFIER.asString()
    }

    // Instance-level registry of known custom qualifier annotation FQ names
    // Populated from FIR-generated hint functions from dependencies
    private val knownCustomQualifiers: Set<String> by lazy { discoverQualifierHints() }

    /**
     * Discover custom qualifier annotations from dependency hint functions.
     *
     * FIR generates `fun qualifier(contributed: MyQualifierAnnotation)` in `org.koin.plugin.hints`.
     * The parameter type encodes the annotation class FQ name.
     */
    private fun discoverQualifierHints(): Set<String> {
        val qualifiers = mutableSetOf<String>()
        try {
            val hintFunctions = context.referenceFunctions(
                CallableId(FqName(KoinPluginConstants.HINTS_PACKAGE), Name.identifier(KoinPluginConstants.QUALIFIER_HINT_NAME))
            )
            for (funcSymbol in hintFunctions) {
                val func = funcSymbol.owner
                for (param in func.valueParameters) {
                    val paramClass = param.type.classifierOrNull?.owner as? IrClass ?: continue
                    val fqName = paramClass.fqNameWhenAvailable?.asString() ?: continue
                    qualifiers.add(fqName)
                    KoinPluginLogger.debug { "  Discovered custom qualifier from hint: $fqName" }
                }
            }
        } catch (e: Exception) {
            KoinPluginLogger.debug { "  Could not discover qualifier hints: ${e.message}" }
        }
        if (qualifiers.isNotEmpty()) {
            KoinPluginLogger.debug { "  Found ${qualifiers.size} custom qualifier annotations from dependency hints" }
        }
        return qualifiers
    }

    /**
     * Find a custom qualifier annotation (an annotation annotated with @Qualifier or @Named).
     * Returns the simple name of the annotation class as the qualifier name.
     *
     * @param annotations List of annotations to search
     * @param logContext Optional context string for debug logging
     * @return The qualifier value, or null if no custom qualifier is found
     */
    fun findCustomQualifierAnnotation(
        annotations: List<IrConstructorCall>,
        logContext: String? = null
    ): QualifierValue? {
        for (annotation in annotations) {
            val annotationClass = annotation.type.classifierOrNull?.owner as? IrClass ?: continue
            val annotationFqName = annotationClass.fqNameWhenAvailable?.asString()

            // Skip standard Kotlin/Android annotations that can't be custom qualifiers
            if (annotationFqName != null && (
                annotationFqName.startsWith("kotlin.") ||
                annotationFqName.startsWith("java.") ||
                annotationFqName.startsWith("android.") ||
                annotationFqName.startsWith("androidx.")
            )) continue

            // Check if this annotation is itself annotated with @Qualifier or @Named (making it a custom qualifier)
            var isCustomQualifier = annotationClass.annotations.any { isQualifierMetaAnnotation(it) }

            // Fallback for cross-module: @Qualifier meta-annotation may not be preserved in Kotlin metadata
            // for deserialized annotation classes from dependencies. Check qualifier hints from dependencies.
            if (!isCustomQualifier && annotationFqName != null && annotationFqName in knownCustomQualifiers) {
                isCustomQualifier = true
                KoinPluginLogger.debug { "  @$annotationFqName confirmed as custom qualifier via dependency hint" }
            }

            if (isCustomQualifier) {
                val qualifierName = annotationClass.name.asString()

                // Check if the annotation has a value argument (e.g., @Dispatcher(NiaDispatchers.IO))
                // Use the argument value as the qualifier to differentiate instances
                val valueArg = try { annotation.getValueArgument(0) } catch (e: Throwable) {
                    KoinPluginLogger.debug { "  Could not read qualifier value argument for @$qualifierName: ${e.message}" }
                    null
                }
                if (valueArg is IrGetEnumValueImpl) {
                    val enumEntry = valueArg.symbol.owner
                    val enumClass = enumEntry.parent as? IrClass
                    val enumFqName = enumClass?.fqNameWhenAvailable?.asString()
                    val enumEntryName = enumEntry.name.asString()
                    val qualifierString = if (enumFqName != null) "$enumFqName.$enumEntryName" else enumEntryName
                    logContext?.let { KoinPluginLogger.debug { "  @$qualifierName($enumEntryName) (custom qualifier) on $it -> named(\"$qualifierString\")" } }
                    return QualifierValue.StringQualifier(qualifierString)
                }
                if (valueArg is IrConst) {
                    val value = valueArg.value
                    if (value is String && value.isNotEmpty()) {
                        logContext?.let { KoinPluginLogger.debug { "  @$qualifierName(\"$value\") (custom qualifier) on $it" } }
                        return QualifierValue.StringQualifier(value)
                    }
                }

                // Fallback: use annotation class name
                logContext?.let { KoinPluginLogger.debug { "  @$qualifierName (custom qualifier) on $it" } }
                return QualifierValue.StringQualifier(qualifierName)
            }
        }
        return null
    }

    // ================================================================================
    // IR Call Creation Methods
    // ================================================================================

    /**
     * Create an IR call for a named() qualifier function.
     *
     * @param qualifierName The string qualifier name
     * @param builder The IR builder
     * @return An IR call to named("qualifierName"), or irNull() if the function isn't available
     */
    fun createNamedQualifierCall(qualifierName: String, builder: DeclarationIrBuilder): IrExpression {
        val namedFunc = namedFunctionSymbol?.owner ?: return builder.irNull()
        return builder.irCall(namedFunc.symbol).apply {
            putValueArgument(0, builder.irString(qualifierName))
        }
    }

    /**
     * Create an IR call for a typeQualifier<T>() function.
     *
     * @param qualifierClass The class to use as type qualifier
     * @param builder The IR builder
     * @return An IR call to typeQualifier<T>(), or irNull() if the function isn't available
     */
    fun createTypeQualifierCall(qualifierClass: IrClass, builder: DeclarationIrBuilder): IrExpression {
        val typeQualifierFunc = typeQualifierFunctionSymbol?.owner ?: return builder.irNull()
        val kClass = kClassClass ?: return builder.irNull()

        return builder.irCall(typeQualifierFunc.symbol).apply {
            putTypeArgument(0, qualifierClass.defaultType)

            // Create KClass reference: SomeType::class
            val kClassType = kClass.typeWith(qualifierClass.defaultType)
            val classReference = IrClassReferenceImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = kClassType,
                symbol = qualifierClass.symbol,
                classType = qualifierClass.defaultType
            )
            putValueArgument(0, classReference)
        }
    }

    /**
     * Create an IR call for the appropriate qualifier function based on the qualifier value.
     *
     * @param qualifier The qualifier value (string or type), or null for no qualifier
     * @param builder The IR builder
     * @return An IR call to the appropriate qualifier function, or null if qualifier is null
     */
    fun createQualifierCall(qualifier: QualifierValue?, builder: DeclarationIrBuilder): IrExpression? {
        return when (qualifier) {
            is QualifierValue.StringQualifier -> createNamedQualifierCall(qualifier.name, builder)
            is QualifierValue.TypeQualifier -> createTypeQualifierCall(qualifier.irClass, builder)
            null -> null
        }
    }

    // ================================================================================
    // Annotation Check Methods
    // ================================================================================

    /**
     * Check if a parameter has the @InjectedParam annotation.
     *
     * @param param The parameter to check
     * @return True if the parameter has @InjectedParam
     */
    fun hasInjectedParamAnnotation(param: IrValueParameter): Boolean {
        val hasAnnotation = param.annotations.any { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.INJECTED_PARAM.asString()
        }
        if (hasAnnotation) {
            KoinPluginLogger.debug { "  @InjectedParam on parameter ${param.name}" }
        }
        return hasAnnotation
    }

    /**
     * Check if a parameter has the @Provided annotation.
     * When present, the parameter's type is considered externally provided
     * and skips compile-time safety validation.
     *
     * @param param The parameter to check
     * @return True if the parameter has @Provided
     */
    fun hasProvidedAnnotation(param: IrValueParameter): Boolean {
        val hasAnnotation = param.annotations.any { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.PROVIDED.asString()
        }
        if (hasAnnotation) {
            KoinPluginLogger.debug { "  @Provided on parameter ${param.name}" }
        }
        return hasAnnotation
    }

    /**
     * Get the property key from @Property annotation on a parameter.
     *
     * @param param The parameter to check
     * @return The property key, or null if @Property is not present
     */
    fun getPropertyAnnotationKey(param: IrValueParameter): String? {
        val propertyAnnotation = param.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.PROPERTY.asString()
        } ?: return null

        val valueArg = propertyAnnotation.getValueArgument(0)
        val key = (valueArg as? IrConst)?.value as? String
        if (key != null) {
            KoinPluginLogger.debug { "  @Property(\"$key\") on parameter ${param.name}" }
        }
        return key
    }

    /**
     * Get the scope ID from @ScopeId annotation on a parameter.
     * Supports two forms:
     * - @ScopeId(MyScope::class) → scope ID is the FQ class name
     * - @ScopeId(name = "my_scope") → scope ID is the string value
     *
     * @param param The parameter to check
     * @return The scope ID string, or null if @ScopeId is not present
     */
    fun getScopeIdAnnotationName(param: IrValueParameter): String? {
        val scopeIdAnnotation = param.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == KoinAnnotationFqNames.SCOPE_ID.asString()
        } ?: return null

        // Check for type-based: @ScopeId(MyScope::class) — arg 0 is KClass
        val valueArg = scopeIdAnnotation.getValueArgument(0)
        if (valueArg is IrClassReference) {
            val scopeClassName = (valueArg.classType.classifierOrNull?.owner as? IrClass)
                ?.fqNameWhenAvailable?.asString()
            if (scopeClassName != null) {
                KoinPluginLogger.debug { "  @ScopeId(${scopeClassName}::class) on parameter ${param.name}" }
                return scopeClassName
            }
        }

        // Check for string-based: @ScopeId(name = "my_scope")
        val nameArg = scopeIdAnnotation.getValueArgument(Name.identifier("name"))
        val name = (nameArg as? IrConst)?.value as? String
        if (!name.isNullOrEmpty()) {
            KoinPluginLogger.debug { "  @ScopeId(name = \"$name\") on parameter ${param.name}" }
            return name
        }

        return null
    }
}
