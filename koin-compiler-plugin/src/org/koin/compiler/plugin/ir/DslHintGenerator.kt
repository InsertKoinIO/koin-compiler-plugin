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
class DslHintGenerator(
    private val context: IrPluginContext,
    // Used to re-derive a cross-module DSL provider's requirements from its provided class's
    // constructor (A3 — see discoverDslDefinitionsFromHints). Optional so bare-CLI/test paths that
    // don't need requirement carrying can still construct the generator; when null, cross-module
    // DslDefs keep empty requirements (pre-fix behavior).
    private val parameterAnalyzer: ParameterAnalyzer? = null,
) {

    /**
     * Generate DSL definition hint functions for cross-module discovery.
     * For each DSL definition (single<T>, factory<T>, etc.), generates a hint function
     * in org.koin.plugin.hints that encodes the provided type.
     *
     * Downstream modules discover these via context.referenceFunctions(dsl_<type>).
     */
    /**
     * Emit DSL definition hints for cross-module discovery, batched ONE FILE PER MODULE.
     *
     * Orphan-hint fix: previously this emitted one synthetic file PER DEFINITION, named by the
     * definition's target type. Removing a `single<T>()` left that per-def file's `.class` behind —
     * IC/build never deleted it — so the removed provider still looked present (silent false green on
     * an incremental rebuild). Now every DSL definition in the same `module { }` (keyed by
     * [Definition.DslDef.modulePropertyId], falling back to its source file) is batched into ONE
     * stable-named file `koin_dsl_hints_<module>.kt`, regenerated wholesale each compile — exactly how
     * the annotation module-scan hints (`koin_hints_<moduleId>.kt`) already work, which is why they
     * never orphan. A removed definition simply isn't in the regenerated file, and the single class is
     * overwritten, so no stale per-def class can survive.
     *
     * Function shape is unchanged (generic `dsl_<defType>` names → same-signature-distinct overloads
     * within the file), so [discoverDslDefinitionsFromHints] finds them by name exactly as before.
     */
    fun generateDslDefinitionHints(
        moduleFragment: IrModuleFragment,
        dslDefinitions: List<Definition.DslDef>
    ) {
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

        // Group by owning module (its `module { }` val), so each module's hints live in one file.
        // Fallback for a definition with no modulePropertyId: its registration source file's SIMPLE
        // name (path-free → deterministic across machines/CI; the full path is a temp dir under test).
        // Either way it lands in a stable, overwrite-per-compile file — never a per-def file.
        val byModule = dslDefinitions.groupBy { def ->
            def.modulePropertyId
                ?: def.registrationSourceFile?.fileEntry?.name?.substringAfterLast('/')?.substringAfterLast('\\')
                ?: "anonymous"
        }

        for ((groupKey, groupDefs) in byModule) {
            val functions = groupDefs.mapNotNull { buildDslHintFunction(it) }
            if (functions.isEmpty()) continue

            // FIR module data + source anchor from the group (all defs share the module val's file).
            val firModuleData = groupDefs.firstNotNullOfOrNull { extractFirModuleData(it.returnTypeClass) }
                ?: extractFirModuleDataFromModule(moduleFragment)
            if (firModuleData == null) {
                KoinPluginLogger.debug { "  WARN: No FIR module data for DSL module '$groupKey', skipping ${functions.size} hint(s)" }
                continue
            }

            // Anchor on a stable source path (issue #32): the module val's registration file dirties
            // whenever any of its definitions change, so the batched file is regenerated then.
            val anchorDef = groupDefs.firstOrNull { it.registrationSourceFile != null }
            val targetClassFile = groupDefs.firstNotNullOfOrNull { def ->
                try {
                    val entry = def.returnTypeClass.fileEntry
                    if (entry.name.contains("/") || entry.name.contains("\\")) entry.name else null
                } catch (_: NotImplementedError) { null }
            }
            val basePath = anchorDef?.registrationSourceFile?.fileEntry?.name
                ?: targetClassFile
                ?: moduleFragment.files.minByOrNull { it.fileEntry.name }?.fileEntry?.name
                ?: "/synthetic"

            val prefix = HintFilePrefix.of(firModuleData.name.asString())
            val fileName = prefix + buildDslModuleHintFileName(groupKey)
            val fakeNewPath = Path(basePath).parent.resolve(fileName)

            val firFile = buildFile {
                moduleData = firModuleData
                origin = FirDeclarationOrigin.Synthetic.PluginFile
                packageDirective = buildPackageDirective { packageFqName = hintsPackage }
                name = fileName
                sourceFile = syntheticHintSourceFile(fakeNewPath.absolutePathString())
            }
            val hintFile = IrFileImpl(
                fileEntry = NaiveSourceBasedFileEntryImpl(fakeNewPath.absolutePathString()),
                packageFragmentDescriptor = EmptyPackageFragmentDescriptor(moduleFragment.descriptor, hintsPackage),
                module = moduleFragment
            ).also { it.metadata = FirMetadataSource.File(firFile) }

            moduleFragment.addFile(hintFile)
            for (function in functions) {
                hintFile.addChild(function)
                context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
            }
            KoinPluginLogger.debug { "  Generated DSL module hints: $fileName (${functions.size} definition(s))" }
        }
    }

    /** Build one DSL hint function encoding a definition (contributed type + bindings + moduleId +
     *  providerOnly + qualifier). Returns null when the target type has no resolvable FqName. */
    private fun buildDslHintFunction(def: Definition.DslDef): IrSimpleFunction? {
            val targetClass = def.returnTypeClass
            targetClass.fqNameWhenAvailable ?: return null

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

            // Add parameter with the target class type (erased to raw form for generics — see #18)
            val params = mutableListOf<IrValueParameter>()
            val contributedParam = context.irFactory.createValueParameter(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.DEFINED,
                name = Name.identifier("contributed"),
                type = targetClass.hintParameterType(context),
                isAssignable = false,
                symbol = IrValueParameterSymbolImpl(),
                kind = IrParameterKind.Regular,
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
                    type = binding.hintParameterType(context),
                    isAssignable = false,
                    symbol = IrValueParameterSymbolImpl(),
                    kind = IrParameterKind.Regular,
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
                    kind = IrParameterKind.Regular,
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
                    kind = IrParameterKind.Regular,
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
                        kind = IrParameterKind.Regular,
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
                        kind = IrParameterKind.Regular,
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

            function.parameters = params

            // Empty body (stub — hint functions are never called)
            function.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, emptyList())

            // Mark as @Deprecated(HIDDEN) to prevent ObjC export crashes on Native targets
            function.addDeprecatedHiddenAnnotation(context)

            return function
    }

    /** Deterministic, stable file name for a module's batched DSL hints: keyed by the module
     *  group (its `module { }` val, or source-file fallback) — NOT by any single definition — so it
     *  is regenerated wholesale each compile and a removed definition leaves no orphan class. */
    private fun buildDslModuleHintFileName(groupKey: String): String {
        val sanitized = buildString(groupKey.length) {
            for (ch in groupKey) append(if (ch.isLetterOrDigit()) ch else '_')
        }.trim('_').ifEmpty { "anonymous" }
        return "koin_dsl_hints_$sanitized.kt"
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
     *
     * Memoized: the underlying `referenceFunctions` scan is invariant within a single
     * compile, and both `validatePendingCallSites` (A4) and `validateCallSiteHintsFromDependencies`
     * (3.6) call this in the aggregator.
     */
    fun discoverDslDefinitionTypes(): Set<String> = cachedDslDefinitionTypes

    private val cachedDslDefinitionTypes: Set<String> by lazy {
        val types = mutableSetOf<String>()
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

        for (defType in KoinPluginConstants.ALL_DEFINITION_TYPES) {
            val functionName = KoinModuleFirGenerator.dslDefinitionHintFunctionName(defType)
            val hintFunctions = context.referenceFunctions(CallableId(hintsPackage, functionName))

            for (hintFuncSymbol in hintFunctions) {
                val hintFunc = hintFuncSymbol.owner
                for (param in hintFunc.regularParameters) {
                    val paramClass = (param.type.classifierOrNull as? IrClassSymbol)?.owner ?: continue
                    paramClass.fqNameWhenAvailable?.asString()?.let { types.add(it) }
                }
            }
        }

        if (types.isNotEmpty()) {
            KoinPluginLogger.debug { "  Discovered ${types.size} DSL definition types from dependency hints" }
        }

        types
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
                val params = hintFunc.regularParameters
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

                // A3: re-derive requirements from the provided class's constructor — the class is on
                // the consumer's classpath (targetClass), so its constructor is ABI-available, exactly
                // like a cross-module ClassDef. Without this the cross-module DslDef carried ZERO
                // requirements, so a downstream entry point never validated a DSL provider's
                // constructor dependencies (silent false negative — e.g. single<OfflineFirstNewsRepository>()
                // whose Notifier/NetworkDataSource/NewsResourceDao deps went unchecked at the app root).
                // Mirrors how the LOCAL DslDef derives requirements (KoinDSLTransformer.attachA3Metadata).
                definitions.add(Definition.DslDef(
                    irClass = targetClass,
                    definitionType = defType,
                    bindings = bindings,
                    modulePropertyId = modulePropertyId,
                    providerOnly = providerOnly,
                    qualifier = qualifier
                ).also { def ->
                    parameterAnalyzer?.let { def.requirements = it.requirementsForClass(targetClass) }
                    def.origin = SourceOrigin.of(targetClass)
                })
            }
        }

        if (definitions.isNotEmpty()) {
            KoinPluginLogger.debug { "  Discovered ${definitions.size} DSL definitions from dependency hints" }
        }

        return definitions
    }
}
