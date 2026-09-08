package org.koin.compiler.adapter

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

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

    /**
     * Recomputes and replaces [declaration]'s deprecations provider after its
     * annotations changed (e.g. a generated @Deprecated(HIDDEN) marker).
     */
    @KotlinApiChange(
        inVersion = "2.4.0",
        kind = KotlinApiChange.Kind.SIGNATURE,
        note = "getDeprecationsProvider's FirAnnotationContainer receiver was specialized to " +
            "FirCallableDeclaration/FirClassLikeDeclaration; 2.3.20-compiled bytecode throws NoSuchMethodError",
    )
    fun refreshDeprecations(
        declaration: FirCallableDeclaration,
        session: FirSession,
    )

    /**
     * Creates a simple function via [factory].
     *
     * Every call site in the plugin passes the same non-varying shape (final, public-or-local,
     * non-inline/expect/tailrec/operator/infix/external, no container source, not a fake override),
     * so only the genuinely varying parameters are exposed here.
     */
    @KotlinApiChange(
        inVersion = "2.4.20",
        kind = KotlinApiChange.Kind.SIGNATURE,
        note = "IrFactory.createSimpleFunction gained a trailing IrClassSymbol parameter; bytecode " +
            "compiled against an older compiler throws NoSuchMethodError",
    )
    fun createSimpleFunction(
        factory: IrFactory,
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        name: Name,
        visibility: DescriptorVisibility,
        returnType: IrType,
        isSuspend: Boolean,
        symbol: IrSimpleFunctionSymbol,
    ): IrSimpleFunction

    /**
     * Records [mapping] as [annotation]'s name-to-argument mapping.
     *
     * Callers must already have set the corresponding positional arguments; on compilers where the
     * mapping is derived this is the only thing that matters.
     */
    @KotlinApiChange(
        inVersion = "2.4.20",
        kind = KotlinApiChange.Kind.SIGNATURE,
        note = "IrAnnotationImpl.argumentMapping became a read-only view computed from arguments + " +
            "symbol (IrAnnotationArgsView); the setter was removed",
    )
    fun setAnnotationArgumentMapping(
        annotation: IrAnnotation,
        mapping: Map<Name, IrExpression>,
    )

    /** The [ClassId] [qualifier] resolves to, or null if it does not resolve to a class. */
    @KotlinApiChange(
        inVersion = "2.4.20",
        kind = KotlinApiChange.Kind.SIGNATURE,
        note = "FirResolvedQualifier.classId was removed and symbol was renamed to qualifierSymbol; " +
            "2.4.0-compiled bytecode throws NoSuchMethodError",
    )
    fun classIdOf(qualifier: FirResolvedQualifier): ClassId?
}
