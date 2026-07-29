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
 * Shared utilities for reading @Configuration annotation data from a MODULE's own IrClass — not
 * to be confused with reading @KoinApplication(configurations = [...]) off an entry-point class
 * (see KoinStartTransformer's own, differently-named helper for that).
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
 * Extract configuration labels from a MODULE class's own @Configuration annotation.
 * Returns an EMPTY list if the class has no @Configuration annotation at all — a module without
 * @Configuration must never be treated as matching any label (including "default").
 *
 * @Configuration("test", "prod") -> ["test", "prod"]
 * @Configuration() or bare @Configuration -> ["default"]
 * No @Configuration -> []
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
fun extractModuleConfigurationLabels(irClass: IrClass): List<String> {
    val configAnnotation = irClass.annotations.firstOrNull {
        it.type.classFqName?.asString() == configurationFqNameStr
    } ?: return emptyList()

    return parseAnnotationLabelArgs(configAnnotation)
}

@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
private fun parseAnnotationLabelArgs(annotation: IrConstructorCall): List<String> {
    val labels = mutableListOf<String>()

    val valueArg = annotation.getRegularArgument(0)
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

