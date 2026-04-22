package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType

/**
 * Returns a type expression suitable for hint function parameters.
 *
 * For non-generic classes this is just [defaultType]. For generic classes, every type
 * parameter is substituted with `Any?` — a concrete, closed type. Motivation (#18):
 *
 * `defaultType` on `class Foo<T>` carries `T` as a free type parameter. When the plugin
 * puts that type into a synthetic top-level hint function, the Kotlin/Native klib
 * signature mangler crashes with "No container found for type parameter 'T'" because
 * `T` has no scope in the hint function's declaration site. JVM is lenient and accepts
 * it, Konan is strict.
 *
 * Runtime Koin resolves definitions on the erased class (no generic specialisation),
 * so using the raw class as the hint parameter loses no validation fidelity —
 * compile-safety still says "Missing definition: Foo" if nothing provides `Foo`, which
 * matches what Koin would check at runtime.
 */
internal fun IrClass.hintParameterType(context: IrPluginContext): IrType {
    return if (typeParameters.isEmpty()) {
        defaultType
    } else {
        val anyN = context.irBuiltIns.anyNType
        typeWith(*Array(typeParameters.size) { anyN })
    }
}
