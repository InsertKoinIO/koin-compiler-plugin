package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

/**
 * Function creation for generated declarations, routed through the compiler's own
 * `buildFun` builder.
 *
 * Never call `IrFactory.createSimpleFunction` directly. Its 17-argument signature is
 * unstable: Kotlin 2.4.20 appended a trailing `IrClassSymbol` parameter, which turned
 * every direct call in this plugin into a `NoSuchMethodError` at IR generation time —
 * a hard crash on every target, from bytecode that still compiled fine against the
 * 2.3.20 floor (GH #89, #99).
 *
 * `buildFun` and `IrFunctionBuilder`'s setters are byte-identical across 2.3.20 →
 * 2.4.20, and the builder invokes `createSimpleFunction` from *inside* the compiler
 * jar, so churn in that signature resolves against the running compiler instead of
 * against whatever we compiled against. One stable reference replaces thirteen exact
 * signature matches.
 *
 * The builder's defaults already match what this plugin passed explicitly at every
 * site (origin `DEFINED`, visibility `PUBLIC`, `Modality.FINAL`, all flags false,
 * no container source, `UNDEFINED_OFFSET` offsets), so the emitted IR is unchanged —
 * the golden files (`*.fir.ir.txt`) are the check on that.
 */

/**
 * A public top-level hint function: `origin = DEFINED`, `visibility = PUBLIC`.
 *
 * Callers set `parent` and `parameters` afterwards, as before.
 */
internal fun IrFactory.createKoinHintFunction(
    name: Name,
    returnType: IrType,
): IrSimpleFunction = buildFun {
    this.name = name
    this.returnType = returnType
    origin = IrDeclarationOrigin.DEFINED
    visibility = DescriptorVisibilities.PUBLIC
}

/**
 * An anonymous lambda body function: `origin = LOCAL_FUNCTION_FOR_LAMBDA`,
 * `visibility = LOCAL`, `name = <anonymous>`.
 *
 * [isSuspend] is threaded for the monitor transformer, which mirrors its parent
 * function's suspend status.
 */
internal fun IrFactory.createKoinLambdaFunction(
    returnType: IrType,
    isSuspend: Boolean = false,
): IrSimpleFunction = buildFun {
    name = Name.special("<anonymous>")
    this.returnType = returnType
    this.isSuspend = isSuspend
    origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
    visibility = DescriptorVisibilities.LOCAL
}
