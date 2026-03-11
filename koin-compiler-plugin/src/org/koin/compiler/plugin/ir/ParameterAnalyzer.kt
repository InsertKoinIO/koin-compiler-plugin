package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Analyzes constructor/function parameters to extract dependency requirements
 * without generating IR expressions. Used for compile-time safety validation.
 *
 * Mirrors the classification logic in KoinArgumentGenerator but produces
 * data (Requirement) instead of IR call expressions.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class ParameterAnalyzer(
    private val qualifierExtractor: QualifierExtractor
) {

    /**
     * Analyze all value parameters of a constructor.
     */
    fun analyzeConstructor(constructor: IrConstructor): List<Requirement> {
        return constructor.valueParameters.map { analyzeParameter(it) }
    }

    /**
     * Analyze all value parameters of a function.
     */
    fun analyzeFunction(function: IrSimpleFunction): List<Requirement> {
        return function.valueParameters.map { analyzeParameter(it) }
    }

    /**
     * Analyze a single parameter and produce a Requirement describing what it needs.
     */
    fun analyzeParameter(param: IrValueParameter): Requirement {
        val paramType = param.type
        val paramName = param.name.asString()

        // @Property("key") → property injection
        val propertyKey = qualifierExtractor.getPropertyAnnotationKey(param)
        if (propertyKey != null) {
            KoinPluginLogger.debug { "    param '$paramName': @Property(\"$propertyKey\")" }
            return Requirement(
                typeKey = typeKeyFromType(paramType),
                paramName = paramName,
                isNullable = paramType.isMarkedNullable(),
                hasDefault = param.defaultValue != null,
                isInjectedParam = false,
                isLazy = false,
                isList = false,
                isProperty = true,
                propertyKey = propertyKey,
                qualifier = null
            )
        }

        // @InjectedParam → provided at runtime via parametersOf()
        val isInjectedParam = qualifierExtractor.hasInjectedParamAnnotation(param)
        if (isInjectedParam) {
            KoinPluginLogger.debug { "    param '$paramName': @InjectedParam" }
            return Requirement(
                typeKey = typeKeyFromType(paramType),
                paramName = paramName,
                isNullable = paramType.isMarkedNullable(),
                hasDefault = param.defaultValue != null,
                isInjectedParam = true,
                isLazy = false,
                isList = false,
                isProperty = false,
                propertyKey = null,
                qualifier = null
            )
        }

        val qualifier = qualifierExtractor.extractFromParameter(param)

        // Check skipDefaultValues: parameter has default, no qualifier, not nullable
        val hasDefault = param.defaultValue != null
        val isNullable = paramType.isMarkedNullable()

        // Classify the type
        val classifier = paramType.classifierOrNull?.owner
        if (classifier is IrClass) {
            val className = classifier.name.asString()
            val packageName = classifier.packageFqName?.asString()

            // Lazy<T>
            if (className == "Lazy" && packageName == "kotlin") {
                val innerType = (paramType as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
                val innerTypeKey = if (innerType != null) typeKeyFromType(innerType) else typeKeyFromType(paramType)
                // For Lazy<T>, what matters for validation is whether the inner type T is nullable,
                // not whether Lazy itself is nullable. Lazy<Foo?> means getOrNull() for Foo,
                // while Lazy<Foo> means get() for Foo.
                val innerNullable = innerType?.isMarkedNullable() ?: isNullable
                KoinPluginLogger.debug { "    param '$paramName': Lazy<${innerTypeKey.render()}> (innerNullable=$innerNullable, hasDefault=$hasDefault)" }
                return Requirement(
                    typeKey = innerTypeKey,
                    paramName = paramName,
                    isNullable = innerNullable,
                    hasDefault = hasDefault,
                    isInjectedParam = false,
                    isLazy = true,
                    isList = false,
                    isProperty = false,
                    propertyKey = null,
                    qualifier = qualifier
                )
            }

            // List<T>
            if (className == "List" && packageName == "kotlin.collections") {
                val elementType = (paramType as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
                val elementTypeKey = if (elementType != null) typeKeyFromType(elementType) else typeKeyFromType(paramType)
                KoinPluginLogger.debug { "    param '$paramName': List<${elementTypeKey.render()}> (nullable=$isNullable, hasDefault=$hasDefault)" }
                return Requirement(
                    typeKey = elementTypeKey,
                    paramName = paramName,
                    isNullable = isNullable,
                    hasDefault = hasDefault,
                    isInjectedParam = false,
                    isLazy = false,
                    isList = true,
                    isProperty = false,
                    propertyKey = null,
                    qualifier = qualifier
                )
            }
        }

        // Regular type
        val typeKey = typeKeyFromType(paramType)
        val qualifierStr = when (qualifier) {
            is QualifierValue.StringQualifier -> ", qualifier=@Named(\"${qualifier.name}\")"
            is QualifierValue.TypeQualifier -> ", qualifier=@Qualifier(${qualifier.irClass.name}::class)"
            null -> ""
        }
        KoinPluginLogger.debug { "    param '$paramName': ${typeKey.render()} (nullable=$isNullable, hasDefault=$hasDefault$qualifierStr)" }
        return Requirement(
            typeKey = typeKey,
            paramName = paramName,
            isNullable = isNullable,
            hasDefault = hasDefault,
            isInjectedParam = false,
            isLazy = false,
            isList = false,
            isProperty = false,
            propertyKey = null,
            qualifier = qualifier
        )
    }

    companion object {
        /**
         * Build a TypeKey from an IrType.
         */
        fun typeKeyFromType(type: org.jetbrains.kotlin.ir.types.IrType): TypeKey {
            val classifier = type.classifierOrNull?.owner as? IrClass
            val classId = classifier?.let {
                classIdFromIrClass(it)
            }
            return TypeKey(classId, classifier?.fqNameWhenAvailable)
        }

        /**
         * Build a ClassId from an IrClass, correctly handling nested classes.
         * ClassId.topLevel() assumes no nesting, which produces wrong results for inner/nested classes.
         */
        fun classIdFromIrClass(irClass: IrClass): ClassId? {
            val fqName = irClass.fqNameWhenAvailable ?: return null
            val packageFqName = irClass.packageFqName ?: return ClassId.topLevel(fqName)
            val relativeClassName = fqName.asString().removePrefix(packageFqName.asString() + ".")
            if (relativeClassName.isEmpty()) return ClassId.topLevel(fqName)
            return ClassId(packageFqName, FqName(relativeClassName), false)
        }
    }
}
