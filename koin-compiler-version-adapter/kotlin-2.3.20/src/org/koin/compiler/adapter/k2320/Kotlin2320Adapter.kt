package org.koin.compiler.adapter.k2320

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
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
 * [KotlinVersionAdapter] for the Kotlin 2.3.20 line — compiled against
 * kotlin-compiler 2.3.20. Never reference this class directly; it is loaded
 * by KotlinAdapterLoader when the running compiler matches.
 */
class Kotlin2320Adapter : KotlinVersionAdapter {

    override val baselineKotlin: String = "2.3.20"

    override fun registerCompilerExtensions(
        storage: CompilerPluginRegistrar.ExtensionStorage,
        firRegistrar: FirExtensionRegistrar,
        irExtension: IrGenerationExtension,
    ) {
        with(storage) {
            FirExtensionRegistrarAdapter.registerExtension(firRegistrar)
            IrGenerationExtension.registerExtension(irExtension)
        }
    }

    override fun setAnnotations(
        target: IrMutableAnnotationContainer,
        annotations: List<IrConstructorCall>,
    ) {
        target.annotations = annotations
    }

    override fun refreshDeprecations(
        declaration: FirCallableDeclaration,
        session: FirSession,
    ) {
        declaration.replaceDeprecationsProvider(declaration.getDeprecationsProvider(session))
    }
}
