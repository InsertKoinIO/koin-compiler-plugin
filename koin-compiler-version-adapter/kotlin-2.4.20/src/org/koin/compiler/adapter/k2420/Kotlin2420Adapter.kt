package org.koin.compiler.adapter.k2420

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProvider
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
class Kotlin2420Adapter : KotlinVersionAdapter {

    override val baselineKotlin: String = "2.4.20"

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

    override fun refreshDeprecations(
        declaration: FirCallableDeclaration,
        session: FirSession,
    ) {
        // 2.4.0: getDeprecationsProvider is receiver-specialized; this bytecode
        // binds to the FirCallableDeclaration overload.
        declaration.replaceDeprecationsProvider(declaration.getDeprecationsProvider(session))
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

    override fun createSimpleFunction(
        factory: IrFactory,
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        returnType: IrType,
        isSuspend: Boolean,
        symbol: IrSimpleFunctionSymbol,
    ): IrSimpleFunction = factory.createSimpleFunction(
        startOffset = startOffset,
        endOffset = endOffset,
        origin = origin,
        name = name,
        visibility = visibility,
        isInline = false,
        isExpect = false,
        returnType = returnType,
        modality = Modality.FINAL,
        symbol = symbol,
        isTailrec = false,
        isSuspend = isSuspend,
        isOperator = false,
        isInfix = false,
        isExternal = false,
        containerSource = null,
        isFakeOverride = false,
    )

    override fun setAnnotationArgumentMapping(
        annotation: IrAnnotation,
        mapping: Map<Name, IrExpression>,
    ) {
        // 2.4.20: argumentMapping is a read-only IrAnnotationArgsView computed from the annotation's
        // arguments and symbol, so it already reflects the positional arguments the caller set.
        // Nothing to record — recording it again is not possible and not needed.
    }

    override fun classIdOf(qualifier: FirResolvedQualifier): ClassId? = qualifier.qualifierSymbol?.classId
}
