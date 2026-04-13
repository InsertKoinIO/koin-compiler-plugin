package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.builder.buildPackageDirective
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.builder.buildFile
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinPluginConstants
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

@OptIn(DeprecatedForRemovalCompilerApi::class)
class DslHintGenerator(private val context: IrPluginContext) {

    /**
     * Generate DSL definition hint functions for cross-module discovery.
     * For each DSL definition (single<T>, factory<T>, etc.), generates a hint function
     * in org.koin.plugin.hints that encodes the provided type.
     *
     * Downstream modules discover these via context.referenceFunctions(dsl_<type>).
     */
    fun generateDslDefinitionHints(
        moduleFragment: IrModuleFragment,
        dslDefinitions: List<Definition.DslDef>
    ) {
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

        for (def in dslDefinitions) {
            val targetClass = def.returnTypeClass
            val targetFqName = targetClass.fqNameWhenAvailable ?: continue
            val targetClassId = ClassId.topLevel(targetFqName)

            val defTypeString = when (def.definitionType) {
                DefinitionType.SINGLE -> KoinPluginConstants.DEF_TYPE_SINGLE
                DefinitionType.FACTORY -> KoinPluginConstants.DEF_TYPE_FACTORY
                DefinitionType.SCOPED -> KoinPluginConstants.DEF_TYPE_SCOPED
                DefinitionType.VIEW_MODEL -> KoinPluginConstants.DEF_TYPE_VIEWMODEL
                DefinitionType.WORKER -> KoinPluginConstants.DEF_TYPE_WORKER
            }
            val hintName = KoinModuleFirGenerator.dslDefinitionHintFunctionName(defTypeString)

            // Build the IR function
            val function = context.irFactory.createSimpleFunction(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.DEFINED,
                name = hintName,
                visibility = DescriptorVisibilities.PUBLIC,
                isInline = false,
                isExpect = false,
                returnType = context.irBuiltIns.unitType,
                modality = Modality.FINAL,
                symbol = IrSimpleFunctionSymbolImpl(),
                isTailrec = false,
                isSuspend = false,
                isOperator = false,
                isInfix = false,
                isExternal = false,
                containerSource = null,
                isFakeOverride = false
            )

            // Add parameter with the target class type (concrete type)
            val params = mutableListOf<IrValueParameter>()
            val contributedParam = context.irFactory.createValueParameter(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.DEFINED,
                name = Name.identifier("contributed"),
                type = targetClass.defaultType,
                isAssignable = false,
                symbol = IrValueParameterSymbolImpl(),
                index = 0,
                varargElementType = null,
                isCrossinline = false,
                isNoinline = false,
                isHidden = false
            )
            contributedParam.parent = function
            params.add(contributedParam)

            // Add binding types as additional parameters
            for ((bindingIndex, binding) in def.bindings.withIndex()) {
                val bindingParam = context.irFactory.createValueParameter(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    origin = IrDeclarationOrigin.DEFINED,
                    name = Name.identifier("binding$bindingIndex"),
                    type = binding.defaultType,
                    isAssignable = false,
                    symbol = IrValueParameterSymbolImpl(),
                    index = bindingIndex + 1,
                    varargElementType = null,
                    isCrossinline = false,
                    isNoinline = false,
                    isHidden = false
                )
                bindingParam.parent = function
                params.add(bindingParam)
            }

            // Encode modulePropertyId as a Unit-typed parameter (cross-module reachability)
            val moduleId = def.modulePropertyId
            if (moduleId != null) {
                val moduleParam = context.irFactory.createValueParameter(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    origin = IrDeclarationOrigin.DEFINED,
                    name = Name.identifier("${KoinPluginConstants.DSL_MODULE_PARAM_PREFIX}${moduleId.replace('.', '$')}"),
                    type = context.irBuiltIns.unitType,
                    isAssignable = false,
                    symbol = IrValueParameterSymbolImpl(),
                    index = params.size,
                    varargElementType = null,
                    isCrossinline = false,
                    isNoinline = false,
                    isHidden = false
                )
                moduleParam.parent = function
                params.add(moduleParam)
            }

            // Encode providerOnly flag (create(::function) definitions)
            if (def.providerOnly) {
                val providerOnlyParam = context.irFactory.createValueParameter(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    origin = IrDeclarationOrigin.DEFINED,
                    name = Name.identifier("providerOnly"),
                    type = context.irBuiltIns.unitType,
                    isAssignable = false,
                    symbol = IrValueParameterSymbolImpl(),
                    index = params.size,
                    varargElementType = null,
                    isCrossinline = false,
                    isNoinline = false,
                    isHidden = false
                )
                providerOnlyParam.parent = function
                params.add(providerOnlyParam)
            }

            // Encode qualifier (same pattern as annotation hints)
            val defQualifier = def.qualifier
            when (defQualifier) {
                is QualifierValue.StringQualifier -> {
                    // String qualifier: "qualifier_<name>" with Unit type
                    val qualifierParam = context.irFactory.createValueParameter(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        origin = IrDeclarationOrigin.DEFINED,
                        name = Name.identifier("qualifier_${KoinPluginConstants.sanitizeQualifierName(defQualifier.name)}"),
                        type = context.irBuiltIns.unitType,
                        isAssignable = false,
                        symbol = IrValueParameterSymbolImpl(),
                        index = params.size,
                        varargElementType = null,
                        isCrossinline = false,
                        isNoinline = false,
                        isHidden = false
                    )
                    qualifierParam.parent = function
                    params.add(qualifierParam)
                }
                is QualifierValue.TypeQualifier -> {
                    // Type qualifier: "qualifierType" with the qualifier class type
                    val qualifierParam = context.irFactory.createValueParameter(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        origin = IrDeclarationOrigin.DEFINED,
                        name = Name.identifier("qualifierType"),
                        type = defQualifier.irClass.defaultType,
                        isAssignable = false,
                        symbol = IrValueParameterSymbolImpl(),
                        index = params.size,
                        varargElementType = null,
                        isCrossinline = false,
                        isNoinline = false,
                        isHidden = false
                    )
                    qualifierParam.parent = function
                    params.add(qualifierParam)
                }
                null -> {}
            }

            function.valueParameters = params

            // Empty body (stub — hint functions are never called)
            function.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, emptyList())

            // Mark as @Deprecated(HIDDEN) to prevent ObjC export crashes on Native targets
            function.addDeprecatedHiddenAnnotation(context)

            // Build deterministic file name
            val fileName = buildDslHintFileName(targetClassId, hintName)

            // Create synthetic FirFile for metadata
            val firModuleData = extractFirModuleData(targetClass)
                ?: extractFirModuleDataFromModule(moduleFragment)
            if (firModuleData == null) {
                KoinPluginLogger.debug { "  WARN: No FIR module data for ${targetClass.name}, skipping DSL hint" }
                continue
            }

            val firFile = buildFile {
                moduleData = firModuleData
                origin = FirDeclarationOrigin.Synthetic.PluginFile
                packageDirective = buildPackageDirective { packageFqName = hintsPackage }
                name = fileName
            }

            // Create synthetic IrFile
            val sourceFileEntry = try {
                val entry = targetClass.fileEntry
                if (entry.name.contains("/") || entry.name.contains("\\")) entry
                else null
            } catch (_: NotImplementedError) {
                null
            }

            // Use target class file entry if available, otherwise use first file in module
            val basePath = sourceFileEntry?.name
                ?: moduleFragment.files.firstOrNull()?.fileEntry?.name
                ?: "/synthetic"
            val fakeNewPath = Path(basePath).parent.resolve(fileName)

            val hintFile = IrFileImpl(
                fileEntry = NaiveSourceBasedFileEntryImpl(fakeNewPath.absolutePathString()),
                packageFragmentDescriptor = EmptyPackageFragmentDescriptor(
                    moduleFragment.descriptor,
                    hintsPackage
                ),
                module = moduleFragment
            ).also { it.metadata = FirMetadataSource.File(firFile) }

            moduleFragment.addFile(hintFile)
            hintFile.addChild(function)

            // Register for downstream visibility
            context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)

            val bindingNames = def.bindings.mapNotNull { it.fqNameWhenAvailable?.shortName()?.asString() }
            val bindingInfo = if (bindingNames.isNotEmpty()) " (bindings: ${bindingNames.joinToString()})" else ""
            KoinPluginLogger.debug { "  Generated DSL hint: $hintName -> ${targetClass.name}$bindingInfo" }
        }
    }

    /** Extract FIR module data from an IR class's metadata. */
    fun extractFirModuleData(irClass: IrClass): org.jetbrains.kotlin.fir.FirModuleData? {
        return when (val src = irClass.metadata) {
            is FirMetadataSource.Class -> src.fir.moduleData
            is FirMetadataSource.Function -> src.fir.moduleData
            is FirMetadataSource.File -> src.fir.moduleData
            else -> null
        }
    }

    private fun extractFirModuleDataFromModule(moduleFragment: IrModuleFragment): org.jetbrains.kotlin.fir.FirModuleData? {
        return moduleFragment.files.firstNotNullOfOrNull { file ->
            when (val meta = file.metadata) {
                is FirMetadataSource.File -> meta.fir.moduleData
                is FirMetadataSource.Class -> meta.fir.moduleData
                else -> null
            }
        }
    }

    /** Build a deterministic file name for a DSL hint function. */
    fun buildDslHintFileName(targetClassId: ClassId, hintName: Name): String {
        val parts = sequence {
            yieldAll(targetClassId.packageFqName.pathSegments().map { it.asString() })
            yield(targetClassId.shortClassName.asString())
            yield(hintName.asString())
        }
        val fileName = parts
            .map { segment -> segment.replaceFirstChar { it.uppercaseChar() } }
            .joinToString(separator = "")
            .replaceFirstChar { it.lowercaseChar() }
        return "$fileName.kt"
    }

    /**
     * Discover DSL definition types from hint functions in dependencies.
     * Queries dsl_single, dsl_factory, etc. hint functions and extracts
     * all provided types (concrete + bindings).
     */
    fun discoverDslDefinitionTypes(): Set<String> {
        val types = mutableSetOf<String>()
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

        for (defType in KoinPluginConstants.ALL_DEFINITION_TYPES) {
            val functionName = KoinModuleFirGenerator.dslDefinitionHintFunctionName(defType)
            val hintFunctions = context.referenceFunctions(CallableId(hintsPackage, functionName))

            for (hintFuncSymbol in hintFunctions) {
                val hintFunc = hintFuncSymbol.owner
                for (param in hintFunc.valueParameters) {
                    val paramClass = (param.type.classifierOrNull as? IrClassSymbol)?.owner ?: continue
                    paramClass.fqNameWhenAvailable?.asString()?.let { types.add(it) }
                }
            }
        }

        if (types.isNotEmpty()) {
            KoinPluginLogger.debug { "  Discovered ${types.size} DSL definition types from dependency hints" }
        }

        return types
    }

    /**
     * Discover DSL definitions from dependency hints as Definition objects.
     * Returns synthetic DslDef objects that serve as providers in graph validation.
     */
    fun discoverDslDefinitionsFromHints(): List<Definition.DslDef> {
        val definitions = mutableListOf<Definition.DslDef>()
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE
        val defTypeMapping = mapOf(
            KoinPluginConstants.DEF_TYPE_SINGLE to DefinitionType.SINGLE,
            KoinPluginConstants.DEF_TYPE_FACTORY to DefinitionType.FACTORY,
            KoinPluginConstants.DEF_TYPE_SCOPED to DefinitionType.SCOPED,
            KoinPluginConstants.DEF_TYPE_VIEWMODEL to DefinitionType.VIEW_MODEL,
            KoinPluginConstants.DEF_TYPE_WORKER to DefinitionType.WORKER
        )

        for ((defTypeStr, defType) in defTypeMapping) {
            val functionName = KoinModuleFirGenerator.dslDefinitionHintFunctionName(defTypeStr)
            val hintFunctions = context.referenceFunctions(CallableId(hintsPackage, functionName))

            for (hintFuncSymbol in hintFunctions) {
                val hintFunc = hintFuncSymbol.owner
                val params = hintFunc.valueParameters
                if (params.isEmpty()) continue

                // First param is the concrete type, remaining are bindings
                val targetClass = (params[0].type.classifierOrNull as? IrClassSymbol)?.owner ?: continue
                val modulePrefix = KoinPluginConstants.DSL_MODULE_PARAM_PREFIX
                val qualifierPrefix = "qualifier_"
                val metaParamNames = setOf("providerOnly", "qualifierType")
                val bindings = params.drop(1)
                    .filter { val name = it.name.asString()
                        !name.startsWith(modulePrefix) && !name.startsWith(qualifierPrefix) && name !in metaParamNames
                    }
                    .mapNotNull { param ->
                        (param.type.classifierOrNull as? IrClassSymbol)?.owner
                    }
                val modulePropertyId = params
                    .firstOrNull { it.name.asString().startsWith(modulePrefix) }
                    ?.name?.asString()
                    ?.removePrefix(modulePrefix)
                    ?.replace('$', '.')
                val providerOnly = params.any { it.name.asString() == "providerOnly" }
                // Decode qualifier: string qualifier (qualifier_<name>) or type qualifier (qualifierType with class type)
                val stringQualifierParam = params.firstOrNull { it.name.asString().startsWith(qualifierPrefix) }
                val typeQualifierParam = params.firstOrNull { it.name.asString() == "qualifierType" }
                val qualifier = when {
                    stringQualifierParam != null -> QualifierValue.StringQualifier(
                        KoinPluginConstants.unsanitizeQualifierName(stringQualifierParam.name.asString().removePrefix(qualifierPrefix))
                    )
                    typeQualifierParam != null -> {
                        val qualifierClass = (typeQualifierParam.type.classifierOrNull as? IrClassSymbol)?.owner
                        qualifierClass?.let { QualifierValue.TypeQualifier(it) }
                    }
                    else -> null
                }

                definitions.add(Definition.DslDef(
                    irClass = targetClass,
                    definitionType = defType,
                    bindings = bindings,
                    modulePropertyId = modulePropertyId,
                    providerOnly = providerOnly,
                    qualifier = qualifier
                ))
            }
        }

        if (definitions.isNotEmpty()) {
            KoinPluginLogger.debug { "  Discovered ${definitions.size} DSL definitions from dependency hints" }
        }

        return definitions
    }
}
