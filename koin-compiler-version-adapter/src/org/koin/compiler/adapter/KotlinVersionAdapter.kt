package org.koin.compiler.adapter

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall

/**
 * Version-split compiler operations.
 *
 * The plugin core is written against the compiler APIs shared by all supported
 * Kotlin versions. The few operations whose binary or source contract differs
 * between versions live here, with one implementation per supported Kotlin line
 * (each compiled against its own kotlin-compiler artifact and selected at plugin
 * load by [KotlinAdapterLoader]).
 *
 * Keep this interface minimal: a member is only justified by a concrete
 * incompatibility, documented via [KotlinApiChange].
 */
interface KotlinVersionAdapter {

    /** Kotlin version this adapter was compiled against (e.g. "2.3.20"). */
    val baselineKotlin: String

    /**
     * Registers the plugin's FIR and IR extensions.
     */
    @KotlinApiChange(
        inVersion = "2.4.0",
        kind = KotlinApiChange.Kind.SIGNATURE,
        note = "FirExtensionRegistrarAdapter.Companion stopped extending ProjectExtensionDescriptor; " +
            "registration bytecode compiled against an older compiler fails with a ClassCastException (GH #19)",
    )
    fun registerCompilerExtensions(
        storage: CompilerPluginRegistrar.ExtensionStorage,
        firRegistrar: FirExtensionRegistrar,
        irExtension: IrGenerationExtension,
    )

    /**
     * Replaces [target]'s annotation list with [annotations].
     */
    @KotlinApiChange(
        inVersion = "2.4.0",
        kind = KotlinApiChange.Kind.SIGNATURE,
        note = "IrAnnotationContainer.annotations became List<IrAnnotation>; assigning List<IrConstructorCall> " +
            "no longer compiles and requires per-version conversion",
    )
    fun setAnnotations(
        target: IrMutableAnnotationContainer,
        annotations: List<IrConstructorCall>,
    )
}
