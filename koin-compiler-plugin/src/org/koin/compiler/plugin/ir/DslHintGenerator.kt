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
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinDiagnostic
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

    // Requirement hint param names — see [buildRequirementParams].
    private val REQ_PARAM_NAME = Regex("^req(\\d+)_(.+)$")
    private val REQ_STRING_QUALIFIER = Regex("^req(\\d+)qn_(.+)$")
    private val REQ_TYPE_QUALIFIER = Regex("^req(\\d+)qt$")

    /** Build one value parameter, parented to [function]. Shared boilerplate for synthetic hint functions. */
    private fun newValueParameter(function: IrSimpleFunction, name: Name, type: IrType): IrValueParameter =
        context.irFactory.createValueParameter(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = name,
            type = type,
            isAssignable = false,
            symbol = IrValueParameterSymbolImpl(),
            kind = IrParameterKind.Regular,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false
        ).also { it.parent = function }

    /**
     * Emit DSL definition hints for cross-module discovery, batched ONE FILE PER MODULE (keyed by
     * [Definition.DslDef.modulePropertyId], falling back to the source file). Downstream modules
     * discover these via `context.referenceFunctions(dsl_<type>)`.
     *
     * Orphan-hint fix: this used to emit one file PER DEFINITION, so removing a `single<T>()` left
     * its `.class` behind — IC never deleted it, a silent false green on an incremental rebuild.
     * Batching into one file per module, regenerated wholesale each compile, means a removed
     * definition is simply absent from the regenerated file — mirrors how the annotation
     * module-scan hints (`koin_hints_<moduleId>.kt`) already work.
     */
    fun generateDslDefinitionHints(
        moduleFragment: IrModuleFragment,
        dslDefinitions: List<Definition.DslDef>,
        moduleIncludes: Map<String, List<String>> = emptyMap(),
        allModuleIds: Set<String> = emptySet(),
        modulesWithIncompleteIncludes: Set<String> = emptySet()
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

        // A module that only relays (`module { includes(a, b) }` with no definitions of its own) has
        // no group above, but its edges still have to reach consumers — otherwise a relay breaks the
        // transitive walk. Union the include owners in so those get a file too.
        val groupKeys = LinkedHashSet<String>().apply {
            addAll(byModule.keys)
            addAll(moduleIncludes.keys)
            addAll(allModuleIds)
        }

        // KOIN-D008 (#75, Tier 1): two distinct module ids that flatten to the same identifier
        // would collide in every hint function name keyed by flattenFqNameForHint (dslincludes_*
        // today). Detect same-compilation collisions before generating anything for the
        // colliding ids, so the build fails with a clear diagnostic instead of a silently-wrong
        // hint (JVM) or a location-less KLIB SignatureClashDetector crash (native/wasm).
        val collidingGroupKeys: Set<String> = run {
            val byEncoded = groupKeys.groupBy { KoinPluginConstants.flattenFqNameForHint(it) }
            val colliding = mutableSetOf<String>()
            for ((encoded, rawIds) in byEncoded) {
                if (rawIds.size <= 1) continue
                for (i in 1 until rawIds.size) {
                    KoinPluginLogger.report(KoinDiagnostic.DuplicateModuleHintIdentity(rawIds[0], rawIds[i], encoded))
                }
                colliding.addAll(rawIds)
            }
            colliding
        }

        for (groupKey in groupKeys) {
            if (groupKey in collidingGroupKeys) continue
            val groupDefs = byModule[groupKey].orEmpty()
            val functions = groupDefs.mapNotNull { buildDslHintFunction(it) }.toMutableList()
            // Topology carrier: this module's own `includes()` edges, in the same per-module file so
            // they are regenerated wholesale and can never orphan (same reason as the defs above).
            val edges = moduleIncludes[groupKey].orEmpty()
            val edgesIncomplete = groupKey in modulesWithIncompleteIncludes
            buildDslIncludesHintFunction(groupKey, edges, incomplete = edgesIncomplete)
                ?.let { functions.add(it) }

            // Keep-alive marker. A module that currently contributes NOTHING — last definition or
            // last `includes()` deleted — would otherwise produce no file, leaving the previous
            // compile's class on disk for IC to find: the module still looks populated and the
            // removal goes undetected (verified on app-dsl — incremental AND `:module:clean` both
            // false-greened; only a full clean caught it). Emitting a zero-parameter includes hint
            // keeps the file written and regenerated wholesale, so the stale class is overwritten.
            // Decoding reads zero `module_` params → no edges, which is the truth.
            if (functions.isEmpty() && groupKey in allModuleIds) {
                buildDslIncludesHintFunction(groupKey, emptyList(), emitWhenEmpty = true)
                    ?.let { functions.add(it) }
            }
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

            val fileName = HintFileNaming.fileName(
                "koin_dsl_hints_", KoinPluginLogger.moduleId, firModuleData.name.asString(), groupKey,
            )
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
            val includeCount = moduleIncludes[groupKey].orEmpty().size
            KoinPluginLogger.debug {
                "  Generated DSL module hints: $fileName (${groupDefs.size} definition(s)" +
                    if (includeCount > 0) ", $includeCount includes edge(s))" else ")"
            }
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

        // Target class type erased to raw form for generics — see #18.
        val contributedParam = newValueParameter(function, Name.identifier("contributed"), targetClass.hintParameterType(context))

        val params = mutableListOf(contributedParam)
        params += buildBindingParams(function, def.bindings)
        // The definition's own REAL requirement types (req$i), not a guess re-derived from
        // targetClass's constructor — see [buildRequirementParams].
        params += buildRequirementParams(function, def.requirements)
        // Cross-module reachability marker.
        def.modulePropertyId?.let { params += buildModuleIdParam(function, it) }
        // create(::function) definitions.
        if (def.providerOnly) params += newValueParameter(function, Name.identifier("providerOnly"), context.irBuiltIns.unitType)
        buildQualifierParam(function, def.qualifier)?.let { params += it }

        function.parameters = params

        // Empty body (stub — hint functions are never called)
        function.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, emptyList())

        // Mark as @Deprecated(HIDDEN) to prevent ObjC export crashes on Native targets
        function.addDeprecatedHiddenAnnotation(context)

        return function
    }

    private fun buildBindingParams(function: IrSimpleFunction, bindings: List<IrClass>): List<IrValueParameter> =
        bindings.mapIndexed { index, binding ->
            newValueParameter(function, Name.identifier("binding$index"), binding.hintParameterType(context))
        }

    /**
     * Encode a definition's own requirement types (req0, req1, …) behind a `reqsEncoded` marker,
     * so a consumer discovers the REAL dependencies instead of re-deriving them by guessing from
     * the provided class's constructor — wrong for providerOnly and for create(::function), where
     * the referenced function's params (not the return type's constructor) are the real deps.
     * `reqsEncoded` distinguishes "zero requirements, correctly encoded" from an old hint with no
     * requirement info at all — see [discoverDslDefinitionsFromHints].
     *
     * Each `req<i>_<paramName>` is immediately followed by AT MOST ONE qualifier companion, tied to
     * it by index (mirrors KoinAnnotationProcessor's funcreqs carrier's `r_`/`qn_`/`qt` convention):
     *   req<i>qn_<sanitized-value> : Unit                    — @Named / string qualifier
     *   req<i>qt                   : <qualifier annotation>  — typed @Qualifier(T::class)
     * Dropping a requirement's qualifier here previously matched it against the wrong (or no)
     * cross-module provider — e.g. an unqualified guess for a `@Named(...) CoroutineDispatcher`.
     *
     * All-or-nothing: if ANY requirement's type has no resolvable ClassId, this returns empty
     * (no `reqsEncoded` marker either) rather than encoding a partial list — a partial list marked
     * "encoded" would tell the consumer the requirements are complete when one is silently missing,
     * permanently hiding that dependency from cross-module validation. Mirrors how
     * KoinAnnotationProcessor's analogous funcreqs carrier aborts the whole hint on the same case.
     */
    private fun buildRequirementParams(function: IrSimpleFunction, requirements: List<Requirement>): List<IrValueParameter> {
        val resolved = mutableListOf<Triple<String, IrClass, QualifierValue?>>()
        for (req in requirements) {
            val reqClassId = req.typeKey.classId ?: return emptyList()
            val reqClass = context.referenceClass(reqClassId)?.owner ?: return emptyList()
            resolved += Triple(req.paramName, reqClass, req.qualifier)
        }
        val params = mutableListOf(newValueParameter(function, Name.identifier("reqsEncoded"), context.irBuiltIns.unitType))
        resolved.forEachIndexed { index, (paramName, reqClass, qualifier) ->
            params += newValueParameter(function, Name.identifier("req${index}_$paramName"), reqClass.hintParameterType(context))
            when (qualifier) {
                is QualifierValue.StringQualifier -> params += newValueParameter(
                    function,
                    Name.identifier("req${index}qn_${KoinPluginConstants.sanitizeQualifierName(qualifier.name)}"),
                    context.irBuiltIns.unitType
                )
                is QualifierValue.TypeQualifier -> params += newValueParameter(
                    function, Name.identifier("req${index}qt"), qualifier.irClass.hintParameterType(context)
                )
                null -> {}
            }
        }
        return params
    }

    /** Unit-typed marker `module_<id with . → $>` — cross-module reachability, shared with [buildDslIncludesHintFunction]. */
    private fun buildModuleIdParam(function: IrSimpleFunction, moduleId: String): IrValueParameter =
        newValueParameter(
            function,
            Name.identifier("${KoinPluginConstants.DSL_MODULE_PARAM_PREFIX}${moduleId.replace('.', '$')}"),
            context.irBuiltIns.unitType
        )

    /** Encode qualifier the same way the annotation hints do: string qualifier as a Unit-typed
     *  marker name, type qualifier as a param typed with the qualifier class itself. */
    private fun buildQualifierParam(function: IrSimpleFunction, qualifier: QualifierValue?): IrValueParameter? = when (qualifier) {
        is QualifierValue.StringQualifier -> newValueParameter(
            function,
            Name.identifier("qualifier_${KoinPluginConstants.sanitizeQualifierName(qualifier.name)}"),
            context.irBuiltIns.unitType
        )
        is QualifierValue.TypeQualifier -> newValueParameter(
            function,
            Name.identifier("qualifierType"),
            qualifier.irClass.defaultType
        )
        null -> null
    }

    /**
     * Build the includes-edge hint for one module val — the DSL topology carrier.
     *
     * `module { includes(a, b) }` records its membership in a LAMBDA BODY, which never reaches the
     * ABI, so this re-exposes it as a declaration: `dslincludes_<flattened-owner-id>(module_<a>: Unit,
     * module_<b>: Unit)`. The owner id lives in the function NAME rather than a parameter — every
     * parameter here is `Unit`-typed, so two module vals with the same include count would otherwise
     * get an identical descriptor and clash on JVM, and hard-fail KLIB (native/wasm) serialization.
     *
     * Returns null when the module includes nothing — unless [emitWhenEmpty], the zero-parameter
     * keep-alive form that stops a now-empty module's hint file from orphaning (see the call site).
     */
    private fun buildDslIncludesHintFunction(
        ownerModuleId: String,
        includedModuleIds: List<String>,
        emitWhenEmpty: Boolean = false,
        incomplete: Boolean = false
    ): IrSimpleFunction? {
        val included = includedModuleIds.distinct()
        // An incomplete module MUST emit even with nothing readable — the marker is the payload.
        if (included.isEmpty() && !emitWhenEmpty && !incomplete) return null

        val function = context.irFactory.createSimpleFunction(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier(KoinPluginConstants.dslIncludesHintFunctionName(ownerModuleId)),
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

        // One marker per included module, using the same `module_<id with . → $>` encoding the
        // definition hints use for modulePropertyId, so decoding is symmetric (buildModuleIdParam).
        // The incomplete marker means this module's own includes() had an unreadable argument, so
        // the edge list below is PARTIAL — without it the consumer would treat it as authoritative
        // and report everything beyond it unreachable (a false KOIN-D001/D002/W001).
        val markerParams = if (incomplete) {
            listOf(newValueParameter(function, Name.identifier(KoinPluginConstants.DSL_INCLUDES_INCOMPLETE_MARKER), context.irBuiltIns.unitType))
        } else emptyList()

        function.parameters = markerParams + included.map { includedId -> buildModuleIdParam(function, includedId) }

        function.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, emptyList())
        // @Deprecated(HIDDEN) — same reason as the definition hints: keeps these out of ObjC export.
        function.addDeprecatedHiddenAnnotation(context)
        return function
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

    /** Edges a dependency declared, plus whether that list is known to be PARTIAL. */
    data class ModuleIncludes(val edges: List<String>, val incomplete: Boolean)

    /**
     * Read the `includes()` edges a module val declares, from its cross-module includes hint —
     * the decode half of the DSL topology carrier. [ownerModuleId] is already known to the caller
     * (from `modules(...)` at the entry point, or a previously-decoded edge), so the hint name is
     * reconstructed rather than enumerated.
     *
     * Returns empty when there is no hint (older producer, or a module that includes nothing) —
     * degrades to the pre-carrier behavior, so a missing hint can only cost reachability, never invent it.
     */
    fun discoverModuleIncludesFromHints(ownerModuleId: String): ModuleIncludes =
        cachedModuleIncludes.getOrPut(ownerModuleId) {
            val hintName = Name.identifier(KoinPluginConstants.dslIncludesHintFunctionName(ownerModuleId))
            val callableId = CallableId(KoinModuleFirGenerator.HINTS_PACKAGE, hintName)
            val prefix = KoinPluginConstants.DSL_MODULE_PARAM_PREFIX
            val params = context.referenceFunctions(callableId).flatMap { it.owner.regularParameters }
                .map { it.name.asString() }
            val edges = params.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).replace('$', '.') }
                .distinct()
            val incomplete = params.any { it == KoinPluginConstants.DSL_INCLUDES_INCOMPLETE_MARKER }
            if (edges.isNotEmpty() || incomplete) {
                KoinPluginLogger.debug {
                    "  includes (cross-module hint): $ownerModuleId -> $edges" +
                        if (incomplete) " (PARTIAL — producer could not read all arguments)" else ""
                }
            }
            ModuleIncludes(edges, incomplete)
        }

    /** Memoized per owner id — `referenceFunctions` is invariant within a compile and the
     *  reachability walk can revisit the same module through several paths. */
    private val cachedModuleIncludes = mutableMapOf<String, ModuleIncludes>()

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

                // First param is the concrete type, remaining are bindings (name-prefixed
                // "binding$i" — see buildBindingParams). Deliberately an ALLOW-list, not a deny-list
                // of the other known param kinds: a consumer built before some future param kind is
                // added must still parse correctly, treating anything it doesn't recognize as "not a
                // binding" rather than misreading it as one. This bit a real case once already: a
                // consumer on a plugin older than the req$i requirement params (below) had no reason
                // to exclude them, so it silently misread each dependency as an extra binding —
                // definitions appeared to PROVIDE their own dependencies, masking a real missing one.
                val targetClass = (params[0].type.classifierOrNull as? IrClassSymbol)?.owner ?: continue
                val modulePrefix = KoinPluginConstants.DSL_MODULE_PARAM_PREFIX
                val qualifierPrefix = "qualifier_"
                val bindings = params.drop(1)
                    .filter { it.name.asString().startsWith("binding") }
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

                // Prefer the REAL requirement types the producer encoded (see buildRequirementParams)
                // over guessing from targetClass's constructor. Falls back to the guess only for a
                // hint from a producer module not yet rebuilt with this fix.
                val reqsEncoded = params.any { it.name.asString() == "reqsEncoded" }
                val requirements = if (reqsEncoded) {
                    // Qualifier companions, keyed by the index of the req<i>_ param they belong to.
                    val stringReqQualifiers: Map<Int, QualifierValue> = params.mapNotNull { param ->
                        val m = REQ_STRING_QUALIFIER.matchEntire(param.name.asString()) ?: return@mapNotNull null
                        m.groupValues[1].toInt() to QualifierValue.StringQualifier(
                            KoinPluginConstants.unsanitizeQualifierName(m.groupValues[2])
                        )
                    }.toMap()
                    val typeReqQualifiers: Map<Int, QualifierValue> = params.mapNotNull { param ->
                        val m = REQ_TYPE_QUALIFIER.matchEntire(param.name.asString()) ?: return@mapNotNull null
                        val qClass = (param.type.classifierOrNull as? IrClassSymbol)?.owner ?: return@mapNotNull null
                        m.groupValues[1].toInt() to QualifierValue.TypeQualifier(qClass)
                    }.toMap()
                    val reqQualifiers = stringReqQualifiers + typeReqQualifiers

                    params.mapNotNull { param ->
                        val m = REQ_PARAM_NAME.matchEntire(param.name.asString()) ?: return@mapNotNull null
                        val index = m.groupValues[1].toInt()
                        val paramName = m.groupValues[2]
                        val reqClass = (param.type.classifierOrNull as? IrClassSymbol)?.owner ?: return@mapNotNull null
                        Requirement(
                            typeKey = TypeKey(ParameterAnalyzer.classIdFromIrClass(reqClass), reqClass.fqNameWhenAvailable),
                            paramName = paramName,
                            isNullable = false,
                            hasDefault = false,
                            isInjectedParam = false,
                            isProvided = false,
                            isScopeId = false,
                            scopeIdName = null,
                            isLazy = false,
                            isList = false,
                            isProperty = false,
                            propertyKey = null,
                            qualifier = reqQualifiers[index]
                        )
                    }
                } else {
                    parameterAnalyzer?.requirementsForDslDefinition(targetClass, providerOnly).orEmpty()
                }

                definitions.add(Definition.DslDef(
                    irClass = targetClass,
                    definitionType = defType,
                    bindings = bindings,
                    modulePropertyId = modulePropertyId,
                    providerOnly = providerOnly,
                    qualifier = qualifier
                ).also { def ->
                    def.requirements = requirements
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
