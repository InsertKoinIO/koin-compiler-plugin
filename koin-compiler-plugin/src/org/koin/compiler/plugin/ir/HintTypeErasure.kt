package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.adapter.KotlinAdapterLoader
import org.koin.compiler.plugin.KoinPluginConstants
import org.koin.compiler.plugin.erasedHintClassId

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
internal fun IrClass.hintParameterType(context: IrPluginContext): IrType = when {
    typeParameters.isNotEmpty() -> typeWith(*Array(typeParameters.size) { context.irBuiltIns.anyNType })
    else -> defaultType
}

/** Local classes and anonymous objects have no ClassId: metadata can't serialize them in a hint. */
internal fun IrClass.isNameableCrossModule(): Boolean = classId != null

/**
 * A hint republishing a dependency's definition (a relay, or a scan reaching into a dependency) may
 * not name that dependency's non-public types: invalid IR under `-Xverify-ir`. Each such
 * parameter becomes `Any`, and an `erased<position>_<ClassId>` marker lets [hintParameterClass]
 * resolve the real class.
 */
internal fun IrSimpleFunction.eraseTypesThisModuleCannotName(context: IrPluginContext): IrSimpleFunction = apply {
    val markers = parameters.mapIndexedNotNull { position, param ->
        val irClass = param.type.classOrNull?.owner ?: return@mapIndexedNotNull null
        if (irClass.isNameableFromThisModule()) return@mapIndexedNotNull null
        val classId = irClass.classId ?: return@mapIndexedNotNull null
        param.type = context.irBuiltIns.anyType
        val encodedClassId = KoinPluginConstants.sanitizeQualifierName(classId.asString())
        val markerName = "${KoinPluginConstants.ERASED_TYPE_MARKER_PREFIX}${position}_$encodedClassId"
        newUnitParameter(context, this, markerName)
    }
    if (markers.isNotEmpty()) parameters = parameters + markers
}

/**
 * Resolves the classes hint parameters name, including erased ones. One per compilation: its stubs
 * hold that compilation's IR, so they must not outlive it.
 */
class HintClassResolver(private val context: IrPluginContext) {
    private val notFoundClassStubs = mutableMapOf<ClassId, IrClass>()

    /** The class a hint parameter names: its own type, or the one its `erased<position>_` marker carries. */
    fun parameterClass(params: List<IrValueParameter>, param: IrValueParameter): IrClass? {
        val classId = erasedHintClassId(params.map { it.name }, params.indexOf(param))
            ?: return param.type.classOrNull?.owner
        return classOrStub(classId)
    }

    /** [classId]'s class, or a key-only stand-in when it's off this module's classpath (see [notFoundClassStub]). */
    fun classOrStub(classId: ClassId): IrClass = context.referenceClass(classId)?.owner ?: notFoundClassStub(classId)

    /**
     * Key-only stand-in for a hint class off this module's classpath (a far `implementation`
     * dependency), shaped like Fir2Ir's not-found class (receiver, `Any` supertype) so the definition
     * validates by type exactly as an unerased relay did. Internal, so a further relay erases it again.
     */
    private fun notFoundClassStub(classId: ClassId): IrClass = notFoundClassStubs.getOrPut(classId) {
        val parent: IrDeclarationParent = classId.outerClassId?.let { notFoundClassStub(it) }
            ?: KotlinAdapterLoader.current.createExternalPackageFragment(classId.packageFqName)
        context.irFactory.createClass(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
            name = classId.shortClassName,
            visibility = DescriptorVisibilities.INTERNAL,
            symbol = IrClassSymbolImpl(),
            kind = ClassKind.CLASS,
            modality = Modality.FINAL,
            isExternal = false,
            isCompanion = false,
            isInner = false,
            isData = false,
            isValue = false,
            isExpect = false,
            isFun = false,
            hasEnumEntries = false,
            source = SourceElement.NO_SOURCE,
        ).also {
            it.parent = parent
            it.superTypes = listOf(context.irBuiltIns.anyType)
            it.createThisReceiverParameter()
        }
    }
}

private fun IrClass.isNameableFromThisModule(): Boolean {
    val packageFragment = generateSequence<IrDeclarationParent>(parent) { (it as? IrDeclaration)?.parent }
        .firstOrNull { it is IrPackageFragment }
    val declaredHere = packageFragment is IrFile
    return generateSequence(this) { it.parent as? IrClass }.all {
        val visibility = it.visibility
        visibility == DescriptorVisibilities.PUBLIC ||
            (declaredHere && visibility == DescriptorVisibilities.INTERNAL)
    }
}

internal fun newUnitParameter(context: IrPluginContext, function: IrSimpleFunction, name: String): IrValueParameter =
    context.irFactory.createValueParameter(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        origin = IrDeclarationOrigin.DEFINED,
        name = Name.identifier(name),
        type = context.irBuiltIns.unitType,
        isAssignable = false,
        symbol = IrValueParameterSymbolImpl(),
        kind = IrParameterKind.Regular,
        varargElementType = null,
        isCrossinline = false,
        isNoinline = false,
        isHidden = false,
    ).also { it.parent = function }
