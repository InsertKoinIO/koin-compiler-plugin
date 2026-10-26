package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.builder.buildPackageDirective
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildFile
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFile
import org.koin.compiler.plugin.KoinPluginConstants
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator
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
 * (regenerated wholesale, so a removed hint leaves no orphan class for IC to miss).
 */
internal fun addHintFunctions(
    context: IrPluginContext,
    moduleFragment: IrModuleFragment,
    functions: List<IrSimpleFunction>,
    firModuleData: FirModuleData,
    basePath: String,
    fileName: String,
) {
    val hintFile = moduleFragment.findHintAnchorFile()
        ?: createSyntheticHintFile(moduleFragment, firModuleData, basePath, fileName)
    for (function in functions) {
        hintFile.addChild(function)
        function.parent = hintFile
        context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
    }
}

private fun IrModuleFragment.findHintAnchorFile(): IrFile? = files.firstOrNull { file ->
    file.packageFqName == KoinModuleFirGenerator.HINTS_PACKAGE &&
        file.declarations.any { it is IrSimpleFunction && it.name.asString() == KoinPluginConstants.HINT_ANCHOR_NAME }
}

private fun createSyntheticHintFile(
    moduleFragment: IrModuleFragment,
    firModuleData: FirModuleData,
    basePath: String,
    fileName: String,
): IrFile {
    val fakeNewPath = Path(basePath).parent.resolve(fileName)
    val firFile = buildFile {
        moduleData = firModuleData
        origin = FirDeclarationOrigin.Synthetic.PluginFile
        packageDirective = buildPackageDirective { packageFqName = KoinModuleFirGenerator.HINTS_PACKAGE }
        name = fileName
        // KLIB serialization needs a resolvable io File per file; a null sourceFile fails wasm/js (KT-82395).
        sourceFile = syntheticHintSourceFile(fakeNewPath.absolutePathString())
    }
    val hintFile = IrFileImpl(
        fileEntry = NaiveSourceBasedFileEntryImpl(fakeNewPath.absolutePathString()),
        packageFragmentDescriptor = EmptyPackageFragmentDescriptor(moduleFragment.descriptor, KoinModuleFirGenerator.HINTS_PACKAGE),
        module = moduleFragment,
    ).also { it.metadata = FirMetadataSource.File(firFile) }
    moduleFragment.addFile(hintFile)
    return hintFile
}
