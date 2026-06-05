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
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinDiagnostic
import org.koin.compiler.plugin.KoinPluginConstants
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.ProvidedTypeRegistry
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

@OptIn(DeprecatedForRemovalCompilerApi::class)
class CallSiteValidator(private val context: IrPluginContext) {

    /**
     * A4: Validate pending call-site resolutions against the assembled graph.
     * Simple loop -- no tree walk needed.
     *
     * When a call site can't be resolved locally and no full graph is available,
     * a call-site hint is generated instead of an error. The app module (which has
     * the full graph) will discover and validate these hints in Phase 3.6.
     */
    fun validatePendingCallSites(
        moduleFragment: IrModuleFragment,
        callSites: List<PendingCallSiteValidation>,
        assembledGraphTypes: Set<String>,
        dslDefinitions: List<Definition>,
        annotationProcessor: KoinAnnotationProcessor?,
        dslHintGenerator: DslHintGenerator,
        injectedParamHints: InjectedParamHintGenerator? = null,
    ) {
        val hasFullGraph = assembledGraphTypes.isNotEmpty()

        // Discover DSL definitions from dependency hints (cross-module DSL discovery).
        // Always merge: A3's assembled graph only contains @Module classes' definitions, not
        // DSL definitions loaded via `modules(dslModule)` from upstream modules. The hints
        // generated at Phase 2.5 in upstream compiles cover that gap (e.g. typed
        // `startKoin<MyApp>() { modules(dslModule) }` where dslModule lives in another source set).
        val dslHintTypes = dslHintGenerator.discoverDslDefinitionTypes()

        // Build the set of all known provided types
        val allKnownTypes = buildSet {
            addAll(assembledGraphTypes)
            addAll(dslHintTypes)
            // Add DSL definition types + bindings
            for (def in dslDefinitions) {
                def.returnTypeClass.fqNameWhenAvailable?.asString()?.let { add(it) }
                for (b in def.bindings) { b.fqNameWhenAvailable?.asString()?.let { add(it) } }
            }
            // When no startKoin/koinConfiguration in this compilation unit,
            // fall back to annotation definitions as known types
            if (!hasFullGraph && annotationProcessor != null) {
                for (def in annotationProcessor.getAllKnownDefinitions()) {
                    def.returnTypeClass.fqNameWhenAvailable?.asString()?.let { add(it) }
                    for (b in def.bindings) { b.fqNameWhenAvailable?.asString()?.let { add(it) } }
                }
            }
        }

        // Also check for definition annotations on the target class (heuristic when no graph)
        val definitionAnnotationFqNames: Set<String> by lazy {
            (org.koin.compiler.plugin.KoinAnnotationFqNames.KOIN_DEFINITION_ANNOTATIONS.map { it.asString() } +
                org.koin.compiler.plugin.KoinAnnotationFqNames.JAKARTA_SINGLETON.asString() +
                org.koin.compiler.plugin.KoinAnnotationFqNames.JAVAX_SINGLETON.asString()).toSet()
        }

        // Collect unresolved call sites for deferred validation via hints
        val unresolvedCallSites = mutableListOf<PendingCallSiteValidation>()

        for (callSite in callSites) {
            // Skip @Provided types
            if (ProvidedTypeRegistry.isProvided(callSite.targetFqName)) {
                KoinPluginLogger.debug { "A4: Skip ${callSite.targetFqName} (@Provided)" }
                continue
            }

            // Skip whitelisted framework types
            if (BindingRegistry.isWhitelistedType(callSite.targetFqName)) {
                KoinPluginLogger.debug { "A4: Skip ${callSite.targetFqName} (framework whitelist)" }
                continue
            }

            // Check assembled graph + DSL definitions + DSL hints
            if (callSite.targetFqName in allKnownTypes) {
                KoinPluginLogger.debug { "A4: OK ${callSite.callFunctionName}<${callSite.targetFqName}>() — found in graph" }
                if (injectedParamHints != null) validateInjectedParamShapeAtCallSite(callSite, injectedParamHints)
                continue
            }

            // Heuristic: check if the class has a definition annotation (for cross-module scenarios)
            if (!hasFullGraph) {
                val hasAnnotation = callSite.targetClass.annotations.any { annotation ->
                    @Suppress("DEPRECATION")
                    annotation.type.classFqName?.asString() in definitionAnnotationFqNames
                }
                if (hasAnnotation) {
                    KoinPluginLogger.debug { "A4: OK ${callSite.callFunctionName}<${callSite.targetFqName}>() — has definition annotation" }
                    if (injectedParamHints != null) validateInjectedParamShapeAtCallSite(callSite, injectedParamHints)
                    continue
                }
            }

            // Not resolved locally
            if (!hasFullGraph) {
                // Defer external types (from dependency JARs) — they may be defined in a downstream module.
                // Local types or modules with local DSL definitions should error immediately.
                val isExternalType = callSite.targetClass.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB
                if (isExternalType || dslDefinitions.isEmpty()) {
                    unresolvedCallSites.add(callSite)
                    KoinPluginLogger.debug { "A4: Deferred ${callSite.callFunctionName}<${callSite.targetFqName}>() — will generate call-site hint (external=$isExternalType)" }
                    continue
                }
            }

            // Report error — either full graph available, or local type with local definitions
            KoinPluginLogger.report(
                KoinDiagnostic.MissingCallSite(
                    type = callSite.targetFqName,
                    callFn = callSite.callFunctionName,
                ),
                callSite.filePath, callSite.line, callSite.column
            )
        }

        // Generate call-site hints for unresolved types (deferred validation)
        if (unresolvedCallSites.isNotEmpty()) {
            KoinPluginLogger.debug { "Phase 3.5: Generating ${unresolvedCallSites.size} call-site hints for deferred validation" }
            generateCallSiteHints(moduleFragment, unresolvedCallSites)
        }
    }

    /**
     * Generate call-site hint functions for deferred cross-module validation.
     * Each unresolved call site type gets a `callsite(required: TargetType)` hint function
     * in org.koin.plugin.hints. The app module discovers and validates these in Phase 3.6.
     */
    fun generateCallSiteHints(
        moduleFragment: IrModuleFragment,
        unresolvedCallSites: List<PendingCallSiteValidation>
    ) {
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE
        val hintName = Name.identifier(KoinPluginConstants.CALLSITE_HINT_NAME)

        // Get FIR module data from current module (not from target class which may be external)
        val firModuleData = moduleFragment.files.firstNotNullOfOrNull { file ->
            when (val meta = file.metadata) {
                is FirMetadataSource.File -> meta.fir.moduleData
                is FirMetadataSource.Class -> meta.fir.moduleData
                else -> null
            }
        }
        if (firModuleData == null) {
            KoinPluginLogger.debug { "  WARN: No FIR module data available, skipping call-site hint generation" }
            return
        }

        // Deduplicate by target FQ name
        val uniqueCallSites = unresolvedCallSites.distinctBy { it.targetFqName }

        // Module-specific prefix for hint filenames so two Gradle modules that both
        // koinInject<SameType>() don't produce identical class names — which otherwise
        // trips the Android dex merger (issue #20). Composes the Gradle `project.path`
        // (when available via koin.moduleId) with the FIR module-data name so KMP
        // targets within the same Gradle module also stay distinct.
        val modulePrefix = HintFilePrefix.of(firModuleData.name.asString())
            .ifEmpty { "module__" }

        for (callSite in uniqueCallSites) {
            val targetClass = callSite.targetClass

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

            // Add parameter with the required type (erased to raw form for generics — see #18)
            val requiredParam = context.irFactory.createValueParameter(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.DEFINED,
                name = Name.identifier("required"),
                type = targetClass.hintParameterType(context),
                isAssignable = false,
                symbol = IrValueParameterSymbolImpl(),
                kind = IrParameterKind.Regular,
                varargElementType = null,
                isCrossinline = false,
                isNoinline = false,
                isHidden = false
            )
            requiredParam.parent = function
            function.parameters = listOf(requiredParam)

            // Empty body (stub — hint functions are never called)
            function.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, emptyList())

            // Mark as @Deprecated(HIDDEN) to prevent ObjC export crashes on Native targets
            function.addDeprecatedHiddenAnnotation(context)

            // Build deterministic file name, prefixed by module identifier to keep
            // hint class names unique across Gradle modules (see above — issue #20).
            val sanitizedName = callSite.targetFqName.split(".")
                .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                .replaceFirstChar { it.lowercaseChar() }
            val fileName = "${modulePrefix}${sanitizedName}_callsite.kt"

            val firFile = buildFile {
                moduleData = firModuleData
                origin = FirDeclarationOrigin.Synthetic.PluginFile
                packageDirective = buildPackageDirective { packageFqName = hintsPackage }
                name = fileName
            }

            // Anchor the synthetic hint file on the call site's source file so the path stays
            // stable across incremental rebuilds (see issue #32). Fall back to the
            // alphabetically-first source file in the module — also stable, just less local.
            val basePath = callSite.filePath
                ?: moduleFragment.files.minByOrNull { it.fileEntry.name }?.fileEntry?.name
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

            KoinPluginLogger.debug { "  Generated call-site hint: callsite(${callSite.targetFqName})" }
        }
    }

    /**
     * Phase 3.6: Discover call-site hints from dependency modules and validate them
     * against all known definitions (assembled graph + local DSL + dependency DSL hints).
     *
     * Call-site hints are generated by feature modules that couldn't resolve call sites locally.
     * The module with the full graph (or with all definitions visible) validates them here.
     */
    fun validateCallSiteHintsFromDependencies(
        assembledGraphTypes: Set<String>,
        dslDefinitions: List<Definition>,
        annotationProcessor: KoinAnnotationProcessor?,
        dslHintGenerator: DslHintGenerator
    ) {
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE
        val hintFunctionName = Name.identifier(KoinPluginConstants.CALLSITE_HINT_NAME)

        val hintFunctions = context.referenceFunctions(CallableId(hintsPackage, hintFunctionName))
        if (hintFunctions.isEmpty()) return

        // Build the set of all known provided types
        val allKnownTypes = buildSet {
            addAll(assembledGraphTypes)
            // Add local DSL definitions
            for (def in dslDefinitions) {
                def.returnTypeClass.fqNameWhenAvailable?.asString()?.let { add(it) }
                for (b in def.bindings) { b.fqNameWhenAvailable?.asString()?.let { add(it) } }
            }
            // Add DSL definition hints from dependencies
            addAll(dslHintGenerator.discoverDslDefinitionTypes())
            // Add annotation definitions
            if (annotationProcessor != null) {
                for (def in annotationProcessor.getAllKnownDefinitions()) {
                    def.returnTypeClass.fqNameWhenAvailable?.asString()?.let { add(it) }
                    for (b in def.bindings) { b.fqNameWhenAvailable?.asString()?.let { add(it) } }
                }
            }
        }

        KoinPluginLogger.debug { "Phase 3.6: Validating ${hintFunctions.count()} call-site hints from dependencies (known types: ${allKnownTypes.size})" }

        for (hintFuncSymbol in hintFunctions) {
            val hintFunc = hintFuncSymbol.owner
            val param = hintFunc.regularParameters.firstOrNull() ?: continue
            val targetClass = (param.type.classifierOrNull as? IrClassSymbol)?.owner ?: continue
            val targetFqName = targetClass.fqNameWhenAvailable?.asString() ?: continue

            // Skip @Provided types
            if (ProvidedTypeRegistry.isProvided(targetFqName)) {
                KoinPluginLogger.debug { "A4-deferred: Skip $targetFqName (@Provided)" }
                continue
            }

            // Skip whitelisted framework types
            if (BindingRegistry.isWhitelistedType(targetFqName)) {
                KoinPluginLogger.debug { "A4-deferred: Skip $targetFqName (framework whitelist)" }
                continue
            }

            if (targetFqName in allKnownTypes) {
                KoinPluginLogger.debug { "A4-deferred: OK callsite<$targetFqName> — found in known definitions" }
                continue
            }

            // Try to extract file info from the hint function's parent IrFile
            val hintFilePath = (hintFunc.parent as? IrFile)?.fileEntry?.name
            val hintLine = if (hintFunc.startOffset != UNDEFINED_OFFSET) {
                (hintFunc.parent as? IrFile)?.fileEntry?.getLineNumber(hintFunc.startOffset)?.plus(1) ?: 0
            } else 0
            val hintColumn = if (hintFunc.startOffset != UNDEFINED_OFFSET) {
                (hintFunc.parent as? IrFile)?.fileEntry?.getColumnNumber(hintFunc.startOffset)?.plus(1) ?: 0
            } else 0

            // Report error with best-available location info
            KoinPluginLogger.report(
                KoinDiagnostic.MissingCallSiteDeferred(type = targetFqName),
                hintFilePath, hintLine, hintColumn
            )
        }
    }

    /**
     * Phase 3.1: DSL-only A3 validation.
     * Validates constructor parameters of local DSL definitions against all known definitions
     * (local DSL + dependency DSL hints + annotation definitions).
     * This runs when there's no startKoin<T>() / @KoinApplication (which would trigger the full A3).
     */
    fun validateDslDefinitionGraph(
        dslDefinitions: List<Definition.DslDef>,
        annotationProcessor: KoinAnnotationProcessor?,
        safetyValidator: CompileSafetyValidator,
        dslHintGenerator: DslHintGenerator,
        startKoinModules: List<String> = emptyList(),
        moduleIncludes: Map<String, List<String>> = emptyMap()
    ) {
        val allDefinitions = mutableListOf<Definition>()
        allDefinitions.addAll(dslDefinitions)
        val dependencyDslDefs = dslHintGenerator.discoverDslDefinitionsFromHints()
        allDefinitions.addAll(dependencyDslDefs)
        if (annotationProcessor != null) {
            allDefinitions.addAll(annotationProcessor.getAllKnownDefinitions())
        }

        KoinPluginLogger.debug { "  providers: ${allDefinitions.size} (local DSL=${dslDefinitions.size}, dependency DSL=${dependencyDslDefs.size}, annotations=${allDefinitions.size - dslDefinitions.size - dependencyDslDefs.size})" }

        if (allDefinitions.isEmpty()) return

        val reachableModuleIds = computeReachableModules(startKoinModules, moduleIncludes)
        val allDslDefs = dslDefinitions + dependencyDslDefs.filterIsInstance<Definition.DslDef>()
        val (reachableDefs, unreachableDefs) = partitionByReachability(allDslDefs, reachableModuleIds)

        val providerDefinitions = mutableListOf<Definition>()
        providerDefinitions.addAll(reachableDefs)
        if (annotationProcessor != null) {
            providerDefinitions.addAll(annotationProcessor.getAllKnownDefinitions())
        }

        KoinPluginLogger.debug { "  reachable providers: ${providerDefinitions.size} (reachable DSL=${reachableDefs.size}, unreachable DSL=${unreachableDefs.size})" }

        val defsToValidate = reachableDefs.filter { !(it is Definition.DslDef && it.providerOnly) }
        val registry = BindingRegistry()
        val qualifierExtractor = safetyValidator.qualifierExtractor
        val parameterAnalyzer = ParameterAnalyzer(qualifierExtractor)
        val errorCount = registry.validateModule(
            "DSL graph",
            providerDefinitions,
            parameterAnalyzer,
            qualifierExtractor,
            defsToValidate
        )

        if (unreachableDefs.isNotEmpty()) {
            reportUnreachableModules(unreachableDefs, reachableModuleIds)
        }

        for (def in providerDefinitions) {
            def.returnTypeClass.fqNameWhenAvailable?.asString()?.let { safetyValidator.addAssembledGraphType(it) }
            for (binding in def.bindings) {
                binding.fqNameWhenAvailable?.asString()?.let { safetyValidator.addAssembledGraphType(it) }
            }
        }

        if (errorCount > 0) {
            KoinPluginLogger.debug { "  -> DONE: $errorCount errors found" }
        } else {
            KoinPluginLogger.debug { "  -> DONE: all dependencies satisfied" }
        }
    }

    private fun computeReachableModules(
        startKoinModules: List<String>,
        moduleIncludes: Map<String, List<String>>
    ): Set<String> {
        if (startKoinModules.isEmpty()) return emptySet()
        val reachable = mutableSetOf<String>()
        val queue = ArrayDeque(startKoinModules)
        while (queue.isNotEmpty()) {
            val moduleId = queue.removeFirst()
            if (reachable.add(moduleId)) {
                moduleIncludes[moduleId]?.forEach { included ->
                    if (included !in reachable) queue.add(included)
                }
            }
        }
        KoinPluginLogger.debug { "  Reachable modules: $reachable (from entry: $startKoinModules)" }
        return reachable
    }

    private fun partitionByReachability(
        dslDefinitions: List<Definition.DslDef>,
        reachableModuleIds: Set<String>
    ): Pair<List<Definition.DslDef>, List<Definition.DslDef>> {
        if (reachableModuleIds.isEmpty()) return dslDefinitions to emptyList()
        val reachable = mutableListOf<Definition.DslDef>()
        val unreachable = mutableListOf<Definition.DslDef>()
        for (def in dslDefinitions) {
            val moduleId = def.modulePropertyId
            if (moduleId == null || moduleId in reachableModuleIds) {
                reachable.add(def)
            } else {
                unreachable.add(def)
            }
        }
        return reachable to unreachable
    }

    private fun reportUnreachableModules(
        unreachableDefs: List<Definition.DslDef>,
        reachableModuleIds: Set<String>
    ) {
        val byModule = unreachableDefs.groupBy { it.modulePropertyId ?: "<unknown>" }
        for ((moduleId, defs) in byModule) {
            val typeNames = defs.mapNotNull { it.returnTypeClass.fqNameWhenAvailable?.shortName()?.asString() }
            val shortModuleName = moduleId.substringAfterLast('.')
            KoinPluginLogger.report(
                KoinDiagnostic.UnreachableModule(
                    module = shortModuleName,
                    types = typeNames,
                )
            )
        }
    }

    /**
     * KOIN-D005 / KOIN-D006 — validate `parametersOf(...)` shape at this call site against the
     * target def's `@InjectedParam` slots (looked up locally first, then via the
     * `injectedparams_*` cross-module hint).
     *
     * Decision matrix:
     *   slots == null              → def doesn't need params (or unknown) → no report
     *   !hasParametersLambda       → user forgot `parametersOf` entirely → KOIN-D006
     *   parametersOfArgs == null   → trailing lambda was non-trivial → ambiguous, no report
     *   shape != Ok                → KOIN-D005 with reason ARITY or TYPE
     */
    private fun validateInjectedParamShapeAtCallSite(
        callSite: PendingCallSiteValidation,
        hints: InjectedParamHintGenerator,
    ) {
        val slots = hints.getSlots(callSite.targetFqName) ?: return

        if (!callSite.hasParametersLambda) {
            KoinPluginLogger.report(
                KoinDiagnostic.MissingInjectedParams(
                    target = callSite.targetFqName,
                    expected = BindingRegistry.renderSlots(slots),
                    callFn = callSite.callFunctionName,
                ),
                callSite.filePath, callSite.line, callSite.column,
            )
            return
        }

        val args = callSite.parametersOfArgs
            ?: return // ambiguous lambda (e.g. `{ buildHolder() }`) — skip

        when (val check = BindingRegistry.validateInjectedParamShape(slots, args)) {
            is BindingRegistry.Companion.ShapeCheck.Ok -> { /* validated */ }
            is BindingRegistry.Companion.ShapeCheck.Ambiguous -> {
                KoinPluginLogger.debug { "A4: skip shape check for ${callSite.targetFqName} (ambiguous parametersOf arg)" }
            }
            is BindingRegistry.Companion.ShapeCheck.ArityMismatch -> {
                KoinPluginLogger.report(
                    KoinDiagnostic.MismatchedInjectedParams(
                        target = callSite.targetFqName,
                        expected = BindingRegistry.renderSlots(slots),
                        actual = BindingRegistry.renderArgs(args),
                        reason = KoinDiagnostic.MismatchedInjectedParams.Reason.ARITY,
                    ),
                    callSite.filePath, callSite.line, callSite.column,
                )
            }
            is BindingRegistry.Companion.ShapeCheck.TypeMismatch -> {
                KoinPluginLogger.report(
                    KoinDiagnostic.MismatchedInjectedParams(
                        target = callSite.targetFqName,
                        expected = BindingRegistry.renderSlots(slots),
                        actual = BindingRegistry.renderArgs(args),
                        reason = KoinDiagnostic.MismatchedInjectedParams.Reason.TYPE,
                    ),
                    callSite.filePath, callSite.line, callSite.column,
                )
            }
        }
    }
}
