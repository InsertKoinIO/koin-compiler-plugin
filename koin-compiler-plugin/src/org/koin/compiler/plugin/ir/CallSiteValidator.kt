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
        dslHintGenerator: DslHintGenerator
    ) {
        val hasFullGraph = assembledGraphTypes.isNotEmpty()

        // Discover DSL definitions from dependency hints (cross-module DSL discovery)
        val dslHintTypes = if (!hasFullGraph) dslHintGenerator.discoverDslDefinitionTypes() else emptySet()

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
            KoinPluginLogger.error(
                "Missing definition: ${callSite.targetFqName}\n" +
                "  resolved by: ${callSite.callFunctionName}<${callSite.targetClass.name}>()\n" +
                "  No matching definition found in any declared module.\n" +
                "  Check your declaration with Annotation or DSL.",
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

            // Add parameter with the required type
            val requiredParam = context.irFactory.createValueParameter(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.DEFINED,
                name = Name.identifier("required"),
                type = targetClass.defaultType,
                isAssignable = false,
                symbol = IrValueParameterSymbolImpl(),
                index = 0,
                varargElementType = null,
                isCrossinline = false,
                isNoinline = false,
                isHidden = false
            )
            requiredParam.parent = function
            function.valueParameters = listOf(requiredParam)

            // Empty body (stub — hint functions are never called)
            function.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, emptyList())

            // Mark as @Deprecated(HIDDEN) to prevent ObjC export crashes on Native targets
            function.addDeprecatedHiddenAnnotation(context)

            // Build deterministic file name
            val sanitizedName = callSite.targetFqName.split(".")
                .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                .replaceFirstChar { it.lowercaseChar() }
            val fileName = "${sanitizedName}_callsite.kt"

            val firFile = buildFile {
                moduleData = firModuleData
                origin = FirDeclarationOrigin.Synthetic.PluginFile
                packageDirective = buildPackageDirective { packageFqName = hintsPackage }
                name = fileName
            }

            // Create synthetic IrFile
            val basePath = moduleFragment.files.firstOrNull()?.fileEntry?.name ?: "/synthetic"
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
            val param = hintFunc.valueParameters.firstOrNull() ?: continue
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
            KoinPluginLogger.error(
                "Missing definition: $targetFqName\n" +
                "  Required by a call site in a dependency module (deferred validation).\n" +
                "  No matching definition found in any declared module.\n" +
                "  Check your declaration with Annotation or DSL.",
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
            KoinPluginLogger.error(
                "Module '$shortModuleName' is not loaded at startKoin — ${defs.size} definitions unreachable: ${typeNames.joinToString()}\n" +
                "  Add it to modules() or includes() to make these definitions available"
            )
        }
    }
}
