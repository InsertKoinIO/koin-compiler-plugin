package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.types.classFqName
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginConstants

/**
 * Shared utilities for reading @Configuration annotation data from IR classes.
 * Used by both KoinAnnotationProcessor (A2: configuration-group validation)
 * and KoinStartTransformer (A3: startKoin full-graph validation).
 */

private val configurationFqNameStr = KoinAnnotationFqNames.CONFIGURATION.asString()

/**
 * Check if an IrClass has the @Configuration annotation.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
fun hasConfigurationAnnotation(irClass: IrClass): Boolean {
    return irClass.annotations.any {
        it.type.classFqName?.asString() == configurationFqNameStr
    }
}

/**
 * Extract configuration labels from @Configuration annotation on an IrClass.
 * Returns empty list if the class has no @Configuration annotation.
 *
 * @Configuration("test", "prod") → ["test", "prod"]
 * @Configuration() or @Configuration → ["default"]
 * No @Configuration → []
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
fun extractConfigurationLabels(irClass: IrClass): List<String> {
    val configAnnotation = irClass.annotations.firstOrNull {
        it.type.classFqName?.asString() == configurationFqNameStr
    } ?: return emptyList()

    return parseAnnotationLabelArgs(configAnnotation)
}

/**
 * Parse label arguments from a @Configuration annotation IR constructor call.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
private fun parseAnnotationLabelArgs(annotation: IrConstructorCall): List<String> {
    val labels = mutableListOf<String>()

    val valueArg = annotation.getValueArgument(0)
    when (valueArg) {
        is IrVararg -> {
            for (element in valueArg.elements) {
                if (element is IrConst) {
                    val value = element.value
                    if (value is String) {
                        labels.add(value)
                    }
                }
            }
        }
        is IrConst -> {
            val value = valueArg.value
            if (value is String) {
                labels.add(value)
            }
        }
        else -> {}
    }

    return labels.ifEmpty { listOf(KoinPluginConstants.DEFAULT_LABEL) }
}
