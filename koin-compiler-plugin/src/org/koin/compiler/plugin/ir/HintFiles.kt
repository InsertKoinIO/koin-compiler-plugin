package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.builder.buildPackageDirective
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildFile
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFile
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.koin.compiler.adapter.KotlinAdapterLoader
import org.koin.compiler.plugin.KoinPluginConstants
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

/**
 * Adds IR-built hint [functions] to the module output and registers them as metadata-visible.
 *
 * KLIB metadata only carries a registered declaration for the FirFile that contains it, and only
 * files of the FIR output are serialized (KT-79565, Kotlin 2.4.20+; before that, only when another
 * FIR-output file shared the hints package). A synthetic file is never in the FIR output, so on
 * Native/JS/Wasm its hints were silently dropped (#105, #106, #113). On those platforms FIR declares
 * an anchor in the hints package ([KoinPluginConstants.HINT_ANCHOR_NAME]), and every hint is
 * parented to the anchor's file instead.
 *
 * JVM has no anchor: each batch keeps its own synthetic file, which compiles to its own class
 * (regenerated wholesale, so a removed hint leaves no orphan class for IC to miss). When
 * [sourceBacked], the file's entry is the real source the hints come from ([basePath]), so Kotlin IC
 * maps the class to it and deletes it with that source: a moved or deleted declaration leaves no
 * stale hint class. The class keeps the hint file's own name via `@file:JvmName`.
 * Only a hint that [basePath] recompiles whenever it must change may be [sourceBacked]: under any
 * other file, editing that file deletes the hint and nothing regenerates it. Aggregates of several
 * files, relays and fallback paths pass `false`.
 */
internal fun addHintFunctions(
    context: IrPluginContext,
    moduleFragment: IrModuleFragment,
    functions: List<IrSimpleFunction>,
    firModuleData: FirModuleData,
    basePath: String,
    fileName: String,
    sourceBacked: Boolean,
) {
    val hintFile = moduleFragment.findHintAnchorFile()
        ?: createSyntheticHintFile(
            context,
            moduleFragment,
            firModuleData,
            // JVM only: KLIB keys files by path, a second file at a source's path must not exist there.
            HintFileLocation(basePath, fileName, sourceBacked && firModuleData.platform.isJvm()),
        )
    val taken = hintFile.declarations.filterIsInstance<IrSimpleFunction>().mapTo(mutableSetOf()) { it.signatureKey() }
    for (function in functions) {
        function.makeSignatureUniqueIn(context, taken, position = hintFile.declarations.size)
        hintFile.addChild(function)
        function.parent = hintFile
        context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
    }
}

/**
 * Two hint functions with the same name and parameter types in one file are an invalid class file
 * on JVM and a `SignatureClashDetector` crash when a KLIB is serialized, reported with no source
 * location. Parameter names never reach a signature, only types do, and a hint's identity lives in
 * its names (`module_<id>`, `erased<n>_<ClassId>`, `qualifier_<name>`): two definitions of one type,
 * or of two types this compilation cannot name, have the same signature. On KLIB targets every hint
 * of the compilation lands in the one anchor file, so uniqueness holds per destination file, never
 * per producer batch (#144). Unit-typed `dupN_<position>` markers are appended until the signature
 * is unique. Decoders read parameters by name, so an allow-list ignores them; the one decoder that
 * reads every parameter as content skips them through [isDupMarker].
 *
 * Never drop a duplicate instead: the signature is deliberately not the identity, and two hints
 * with one signature describe two providers.
 */
private fun IrSimpleFunction.makeSignatureUniqueIn(context: IrPluginContext, taken: MutableSet<String>, position: Int) {
    var marker = 0
    while (!taken.add(signatureKey())) {
        marker++
        KoinPluginLogger.debug { "    disambiguated duplicate hint signature: ${signatureKey()} (marker $marker)" }
        parameters = parameters + newUnitParameter(context, this, "dup${marker}_$position")
    }
}

private fun IrSimpleFunction.signatureKey(): String =
    name.asString() + "(" + parameters.joinToString(",") { it.type.render() } + ")"

private val DUP_MARKER = Regex("dup\\d+_\\d+")

/** A signature-uniqueness marker from [makeSignatureUniqueIn], never part of the hint's content. */
internal fun IrValueParameter.isDupMarker(): Boolean = DUP_MARKER.matches(name.asString())

private fun IrModuleFragment.findHintAnchorFile(): IrFile? = files.firstOrNull { file ->
    file.packageFqName == KoinModuleFirGenerator.HINTS_PACKAGE &&
        file.declarations.any { it is IrSimpleFunction && it.name.asString() == KoinPluginConstants.HINT_ANCHOR_NAME }
}

/** Where a synthetic hint file lives: next to [basePath], named [fileName]; [sourceBacked] see [addHintFunctions]. */
private class HintFileLocation(val basePath: String, val fileName: String, sourceBacked: Boolean) {
    val fakePath: java.nio.file.Path = Path(basePath).parent.resolve(fileName)
    val isSourceBacked: Boolean = sourceBacked && File(basePath).isFile
}

private fun createSyntheticHintFile(
    context: IrPluginContext,
    moduleFragment: IrModuleFragment,
    firModuleData: FirModuleData,
    location: HintFileLocation,
): IrFile {
    val firFile = buildFile {
        moduleData = firModuleData
        origin = FirDeclarationOrigin.Synthetic.PluginFile
        packageDirective = buildPackageDirective { packageFqName = KoinModuleFirGenerator.HINTS_PACKAGE }
        name = location.fileName
        // KLIB serialization needs a resolvable io File per file; a null sourceFile fails wasm/js (KT-82395).
        sourceFile = syntheticHintSourceFile(location.fakePath.absolutePathString())
    }
    // The facade class would otherwise be named after the real source (two hint files of one source
    // would then share one class); keep the hint file's own name, or stay on the hint's own path.
    val jvmName = if (location.isSourceBacked) jvmNameAnnotation(context, facadeClassName(location.fileName)) else null
    val entryPath = if (jvmName != null) Path(location.basePath) else location.fakePath
    val hintFile = IrFileImpl(
        fileEntry = NaiveSourceBasedFileEntryImpl(entryPath.absolutePathString()),
        packageFragmentDescriptor = EmptyPackageFragmentDescriptor(moduleFragment.descriptor, KoinModuleFirGenerator.HINTS_PACKAGE),
        module = moduleFragment,
    ).also { it.metadata = FirMetadataSource.File(firFile) }
    jvmName?.let { KotlinAdapterLoader.current.setAnnotations(hintFile, listOf(it)) }
    moduleFragment.addFile(hintFile)
    return hintFile
}

private fun facadeClassName(fileName: String): String =
    fileName.removeSuffix(".kt").replaceFirstChar { it.uppercaseChar() } + "Kt"

private fun jvmNameAnnotation(context: IrPluginContext, name: String): IrConstructorCall? {
    val jvmName = context.referenceClass(ClassId(FqName("kotlin.jvm"), Name.identifier("JvmName")))
    val constructor = jvmName?.owner?.declarations?.filterIsInstance<IrConstructor>()?.firstOrNull { it.isPrimary }
    if (jvmName == null || constructor == null) return null
    val nameArgument = IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.stringType, name)
    return IrAnnotationImpl.fromSymbolOwner(UNDEFINED_OFFSET, UNDEFINED_OFFSET, jvmName.defaultType, constructor.symbol)
        .apply { putRegularArgument(0, nameArgument) }
}
