package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.classFqName
import org.koin.compiler.plugin.KoinAnnotationFqNames

/**
 * Shared utility for reading @Configuration annotation presence from IR classes.
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

