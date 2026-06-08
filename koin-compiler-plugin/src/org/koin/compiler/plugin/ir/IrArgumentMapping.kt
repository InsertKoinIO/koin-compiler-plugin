package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression

/**
 * Helpers over the unified IR parameters/arguments API (Kotlin 2.3.20+).
 *
 * `IrFunction.parameters` lists ALL parameters (dispatch receiver, context,
 * extension receiver, regular) and `IrMemberAccessExpression.arguments` is
 * indexed in lockstep with it. The legacy regular-only accessors
 * (`valueParameters`, `putValueArgument`, `extensionReceiver`, ...) are removed
 * in Kotlin 2.4.0, so all plugin code routes through `parameters`/`arguments`
 * via these helpers — keeping the regular-index mapping in one place.
 */

/** Regular (non-receiver, non-context) parameters — the old `valueParameters` view. */
internal val IrFunction.regularParameters: List<IrValueParameter>
    get() = parameters.filter { it.kind == IrParameterKind.Regular }

/** The extension receiver parameter, if any — the old `extensionReceiverParameter`. */
internal val IrFunction.extensionReceiverParam: IrValueParameter?
    get() = parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }

/** Sets the argument for the callee's [index]-th REGULAR parameter — the old `putValueArgument`. */
internal fun IrFunctionAccessExpression.putRegularArgument(index: Int, value: IrExpression?) {
    val param = symbol.owner.regularParameters[index]
    arguments[param.indexInParameters] = value
}

/** Reads the argument for the callee's [index]-th REGULAR parameter — the old `getValueArgument`. */
internal fun IrFunctionAccessExpression.getRegularArgument(index: Int): IrExpression? {
    val param = symbol.owner.regularParameters.getOrNull(index) ?: return null
    return arguments[param.indexInParameters]
}

/** Number of regular arguments — the old `valueArgumentsCount`. */
internal val IrFunctionAccessExpression.regularArgumentsCount: Int
    get() = symbol.owner.regularParameters.size

/** Sets the extension receiver argument — the old `extensionReceiver =`. */
internal fun IrFunctionAccessExpression.setExtensionReceiverArgument(value: IrExpression?) {
    // A missing extension-receiver parameter means the call site and callee shape
    // disagree — fail loudly rather than silently dropping the argument.
    val param = symbol.owner.extensionReceiverParam
        ?: error("Koin compiler plugin: callee ${symbol.owner.name} has no extension receiver parameter")
    arguments[param.indexInParameters] = value
}

/** Reads the extension receiver argument — the old `extensionReceiver`. */
internal val IrFunctionAccessExpression.extensionReceiverArgument: IrExpression?
    get() {
        val param = symbol.owner.extensionReceiverParam ?: return null
        return arguments[param.indexInParameters]
    }

/** Sets the [index]-th type argument — the old `putTypeArgument`. */
internal fun IrMemberAccessExpression<*>.putTypeArgumentCompat(index: Int, type: org.jetbrains.kotlin.ir.types.IrType?) {
    typeArguments[index] = type
}

/** Reads the [index]-th type argument — the old `getTypeArgument`. */
internal fun IrMemberAccessExpression<*>.getTypeArgumentCompat(index: Int): org.jetbrains.kotlin.ir.types.IrType? =
    typeArguments.getOrNull(index)
