package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * Adds `@Deprecated("Koin compiler plugin internal hint function", level = DeprecationLevel.HIDDEN)`
 * annotation to an IR function.
 *
 * This prevents the function from being exported to ObjC headers on Kotlin/Native,
 * which would otherwise crash with "An operation is not implemented" in findSourceFile.
 *
 * Mirrors the FIR-phase annotation added by [org.koin.compiler.plugin.fir.KoinModuleFirGenerator.markAsDeprecatedHidden].
 */
fun IrSimpleFunction.addDeprecatedHiddenAnnotation(context: IrPluginContext) {
    val deprecatedHiddenAnnotation = buildDeprecatedHiddenAnnotation(context) ?: return
    // In Kotlin version 2.3.20, annotations is a List<IrConstructorCall>
    // In version 2.4.0,it is a List<IrAnnotation> so we have to make sure
    // that the cast is correct.
    annotations = annotations + deprecatedHiddenAnnotation
}

/**
 * Build an `IrAnnotation` representing `@Deprecated(message = "...", level = DeprecationLevel.HIDDEN)`.
 * Returns null if the Deprecated class cannot be resolved.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
private fun buildDeprecatedHiddenAnnotation(context: IrPluginContext): IrAnnotation? {
    // Resolve kotlin.Deprecated class
    val deprecatedClassSymbol = context.referenceClass(StandardClassIds.Annotations.Deprecated) ?: return null
    val deprecatedClass = deprecatedClassSymbol.owner

    // Find the primary constructor (message: String, replaceWith: ReplaceWith, level: DeprecationLevel)
    val constructor = deprecatedClass.declarations
        .filterIsInstance<IrConstructor>()
        .firstOrNull { it.isPrimary }
        ?: return null

    // Resolve DeprecationLevel enum for HIDDEN entry
    val deprecationLevelClassId = ClassId(FqName("kotlin"), Name.identifier("DeprecationLevel"))
    val deprecationLevelClassSymbol = context.referenceClass(deprecationLevelClassId) ?: return null
    val hiddenEntry = deprecationLevelClassSymbol.owner.declarations
        .filterIsInstance<IrEnumEntry>()
        .firstOrNull { it.name.asString() == "HIDDEN" }
        ?: return null

    val messageExpr = IrConstImpl.string(
        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
        context.irBuiltIns.stringType,
        "Koin compiler plugin internal hint function"
    )

    val levelExpr = IrGetEnumValueImpl(
        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
        deprecationLevelClassSymbol.defaultType,
        hiddenEntry.symbol
    )

    // Build the annotation using IrAnnotationImpl
    return IrAnnotationImpl.fromSymbolOwner(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        deprecatedClassSymbol.defaultType,
        constructor.symbol
    ).apply {
        // Set positional value arguments (used by codegen)
        // arg 0: message (String)
        putValueArgument(0, messageExpr)
        // arg 1: replaceWith — leave as default (null)
        // arg 2: level (DeprecationLevel.HIDDEN)
        putValueArgument(2, levelExpr)

        // Also set argument mapping (used by IR annotation processing and metadata serialization)
        argumentMapping = mapOf(
            Name.identifier("message") to messageExpr,
            Name.identifier("level") to levelExpr
        )
    }
}
