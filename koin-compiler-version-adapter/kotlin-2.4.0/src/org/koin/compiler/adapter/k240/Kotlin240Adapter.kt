package org.koin.compiler.adapter.k240

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.koin.compiler.adapter.KotlinVersionAdapter

/**
 * [KotlinVersionAdapter] for the Kotlin 2.4 line — compiled against
 * kotlin-compiler 2.4.0, where the FIR registration contract and the
 * IrAnnotationContainer.annotations type differ from 2.3.x. Never reference
 * this class directly; it is loaded by KotlinAdapterLoader when the running
 * compiler matches.
 */
class Kotlin240Adapter : KotlinVersionAdapter {

    override val baselineKotlin: String = "2.4.0"

    override fun registerCompilerExtensions(
        storage: CompilerPluginRegistrar.ExtensionStorage,
        firRegistrar: FirExtensionRegistrar,
        irExtension: IrGenerationExtension,
    ) {
        // Same source as 2.3.x, but the bytecode binds to the 2.4.0 registration
        // contract (FirExtensionRegistrarAdapter.Companion changed supertype).
        with(storage) {
            FirExtensionRegistrarAdapter.registerExtension(firRegistrar)
            IrGenerationExtension.registerExtension(irExtension)
        }
    }

    override fun setAnnotations(
        target: IrMutableAnnotationContainer,
        annotations: List<IrConstructorCall>,
    ) {
        // 2.4.0: annotations is List<IrAnnotation>; convert what isn't one already.
        // NOTE: conversion shape is finalized against the real 2.4.0 API at compile time.
        target.annotations = annotations.map { it.asIrAnnotation() }
    }

    private fun IrConstructorCall.asIrAnnotation(): org.jetbrains.kotlin.ir.expressions.IrAnnotation =
        this as? org.jetbrains.kotlin.ir.expressions.IrAnnotation
            ?: org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl(
                startOffset = startOffset,
                endOffset = endOffset,
                type = type,
                symbol = symbol,
                typeArgumentsCount = typeArguments.size,
                constructorTypeArgumentsCount = constructorTypeArgumentsCount,
                origin = origin,
                source = source,
            ).also { annotation ->
                for (index in arguments.indices) {
                    annotation.arguments[index] = arguments[index]
                }
            }
}
