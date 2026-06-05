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
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinPluginConstants
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

/**
 * Generates and discovers `injectedparams_<flat-fqn>(...)` hint functions in
 * [KoinPluginConstants.HINTS_PACKAGE] so the plugin can validate `parametersOf(...)` shapes at
 * `get<T>()` / `inject<T>()` / `koinInject<T>()` call sites across module boundaries
 * (KOIN-D005/D006).
 *
 * Design — see the call-site validation plan:
 *
 * The hint *is* the shape. For each definition whose primary constructor (or @Inject constructor,
 * or factory function) has ≥ 1 `@InjectedParam` parameter, a synthetic top-level function is
 * emitted whose value parameters mirror the slot list exactly — same arity, same types, same
 * nullability. A consumer module recovers the shape by walking
 * `IrFunction.valueParameters` — no string parsing, no custom encoding.
 *
 * The local index `localSlots` is populated during the same call to [generateAndIndexHints] so
 * intra-module call-site validation doesn't have to round-trip through the hint mechanism.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class InjectedParamHintGenerator(
    private val context: IrPluginContext,
    private val qualifierExtractor: QualifierExtractor,
) {
    private val parameterAnalyzer = ParameterAnalyzer(qualifierExtractor)
    private val localSlots = mutableMapOf<String, List<InjectedParamSlot>>()
    private val crossModuleCache = mutableMapOf<String, List<InjectedParamSlot>?>()
    // Targets with multiple definitions whose `@InjectedParam` shapes differ (or one defines
    // them and another doesn't). The plugin can't pick which definition a call site resolves
    // to without qualifier-aware resolution, so it skips D005/D006 for ambiguous targets.
    private val ambiguousTargets = mutableSetOf<String>()

    /**
     * Walk [definitions], populate the local slot index, and emit one hint function per
     * definition that has at least one `@InjectedParam` slot. Definitions whose constructor
     * doesn't take `@InjectedParam` parameters produce no hint and no index entry —
     * `getSlots(fqn)` returns `null` for them, which is the "no params expected" signal.
     *
     * Ambiguity guard: if two definitions share the same return-type FqName but disagree on
     * their `@InjectedParam` shape (different lists, or one has params and another doesn't),
     * the target is marked ambiguous and excluded from the index — the plugin can't tell
     * which definition a given call site resolves to without qualifier-aware analysis, so
     * skipping validation is the only sound choice. Typical real-world trigger: multiple
     * `@Singleton fun foo(...): Unit` distinguished only by `@Named` qualifier (a "launcher"
     * pattern: `koin.get<Unit>(named("init")) { parametersOf(...) }`).
     */
    fun generateAndIndexHints(
        moduleFragment: IrModuleFragment,
        definitions: List<Definition>,
    ) {
        if (definitions.isEmpty()) return

        // First pass: collect distinct slot shapes per target type so we can detect ambiguity.
        //
        // ExternalFunctionDef contributes no slot information of its own — its shape was
        // already encoded in the source module's hint. Feeding it in as `emptyList()` would
        // make it compete against a real local shape (`{[slots], []}` → size 2 → ambiguous)
        // and silently disable D005/D006 for that target. So we skip externals entirely
        // here; the local pass owns the shape, and cross-module discovery happens later
        // via `discoverCrossModuleSlots` keyed off the emitted hint.
        val shapesByTarget = mutableMapOf<String, MutableSet<List<InjectedParamSlot>>>()
        val resolvedDefs = mutableListOf<Triple<Definition, IrClass, List<InjectedParamSlot>>>()
        for (def in definitions) {
            if (def is Definition.ExternalFunctionDef) continue
            val targetClass = def.returnTypeClass
            val targetFqName = targetClass.fqNameWhenAvailable?.asString() ?: continue
            val slots = extractSlotsFromDefinition(def) ?: continue
            shapesByTarget.getOrPut(targetFqName) { mutableSetOf() }.add(slots)
            if (slots.isNotEmpty()) resolvedDefs.add(Triple(def, targetClass, slots))
        }

        // Mark types with conflicting shapes as ambiguous.
        for ((target, shapes) in shapesByTarget) {
            if (shapes.size > 1) ambiguousTargets.add(target)
        }

        // Second pass: only index + emit hints for non-ambiguous targets.
        for ((def, targetClass, slots) in resolvedDefs) {
            val targetFqName = targetClass.fqNameWhenAvailable?.asString() ?: continue
            if (targetFqName in ambiguousTargets) {
                KoinPluginLogger.debug { "InjectedParam: ambiguous target $targetFqName (multiple definitions, different @InjectedParam shapes) — skipping index" }
                continue
            }
            localSlots.putIfAbsent(targetFqName, slots)
            emitHintFunction(moduleFragment, def, targetClass, targetFqName, slots)
        }

        if (localSlots.isNotEmpty()) {
            KoinPluginLogger.debug { "InjectedParam hints: indexed ${localSlots.size} definition(s) with @InjectedParam" }
        }
    }

    /**
     * Look up slots for [targetFqName]:
     *   1. Local index from this compilation (populated by [generateAndIndexHints]).
     *   2. Cross-module `injectedparams_*` hint discovered via `referenceFunctions`.
     *
     * Returns `null` when the definition is unknown OR has no `@InjectedParam` slots — both cases
     * mean "no shape constraint" from the caller's perspective. The two are intentionally
     * collapsed because:
     *  - Definitions with zero @InjectedParam params never produce a hint (saves emit cost), so
     *    "no hint found" is the canonical "no @InjectedParam" signal.
     *  - At the call site, both meanings translate to "don't fire D005/D006" — same outcome.
     */
    fun getSlots(targetFqName: String): List<InjectedParamSlot>? {
        if (targetFqName in ambiguousTargets) return null
        localSlots[targetFqName]?.let { return it }
        if (crossModuleCache.containsKey(targetFqName)) return crossModuleCache[targetFqName]
        val discovered = discoverCrossModuleSlots(targetFqName)
        crossModuleCache[targetFqName] = discovered
        return discovered
    }

    /**
     * Extract `@InjectedParam` slot shape from a definition's primary constructor (for class defs)
     * or function (for function-based defs). Returns `null` if the definition has no params source
     * (e.g. ExternalFunctionDef — shape was encoded in the source module's hint already).
     */
    private fun extractSlotsFromDefinition(def: Definition): List<InjectedParamSlot>? {
        val params: List<IrValueParameter> = when (def) {
            is Definition.ClassDef -> findInjectableConstructor(def.irClass)?.regularParameters
            is Definition.DslDef -> findInjectableConstructor(def.irClass)?.regularParameters
            is Definition.FunctionDef -> def.irFunction.regularParameters
            is Definition.TopLevelFunctionDef -> def.irFunction.regularParameters
            is Definition.ExternalFunctionDef -> null
        } ?: return null

        val slots = mutableListOf<InjectedParamSlot>()
        for (p in params) {
            if (!qualifierExtractor.hasInjectedParamAnnotation(p)) continue
            val classifier = p.type.classifierOrNull?.owner as? IrClass ?: continue
            val typeFqName = classifier.fqNameWhenAvailable?.asString() ?: continue
            slots.add(
                InjectedParamSlot(
                    name = p.name.asString(),
                    typeFqName = typeFqName,
                    isNullable = p.type.isMarkedNullable(),
                )
            )
        }
        return slots
    }

    /** Same selection logic as BindingRegistry.findConstructorToUse (kept duplicated to avoid widening API). */
    private fun findInjectableConstructor(targetClass: IrClass): IrConstructor? {
        val injectConstructor = targetClass.declarations
            .filterIsInstance<IrConstructor>()
            .firstOrNull { ctor ->
                ctor.annotations.any { ann ->
                    val fqn = ann.type.classFqName?.asString()
                    fqn == "jakarta.inject.Inject" || fqn == "javax.inject.Inject"
                }
            }
        return injectConstructor ?: targetClass.primaryConstructor
    }

    /**
     * Build and attach the `injectedparams_<flat-fqn>(slot1: T1, slot2: T2, …)` IR function as a
     * synthetic file under [KoinPluginConstants.HINTS_PACKAGE]. Mirrors the file-creation pattern
     * of [DslHintGenerator.generateDslDefinitionHints].
     */
    private fun emitHintFunction(
        moduleFragment: IrModuleFragment,
        def: Definition,
        targetClass: IrClass,
        targetFqName: String,
        slots: List<InjectedParamSlot>,
    ) {
        val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE
        val flat = KoinPluginConstants.flattenFqNameForHint(targetFqName)
        val hintName = Name.identifier("${KoinPluginConstants.INJECTED_PARAMS_HINT_PREFIX}$flat")

        // Re-walk the source params so we can reuse the original IrType for each slot
        // (preserving its nullability) rather than reconstructing it from FqName.
        val sourceParams: List<IrValueParameter> = when (def) {
            is Definition.ClassDef -> findInjectableConstructor(def.irClass)?.regularParameters
            is Definition.DslDef -> findInjectableConstructor(def.irClass)?.regularParameters
            is Definition.FunctionDef -> def.irFunction.regularParameters
            is Definition.TopLevelFunctionDef -> def.irFunction.regularParameters
            else -> null
        } ?: return

        val injectedSourceParams = sourceParams.filter { qualifierExtractor.hasInjectedParamAnnotation(it) }
        if (injectedSourceParams.size != slots.size) {
            KoinPluginLogger.debug { "  InjectedParam hint: slot/source mismatch for $targetFqName — skipping (slots=${slots.size}, source=${injectedSourceParams.size})" }
            return
        }

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
            isFakeOverride = false,
        )

        val params = mutableListOf<IrValueParameter>()
        for ((index, sourceParam) in injectedSourceParams.withIndex()) {
            val slot = slots[index]
            val classifier = sourceParam.type.classifierOrNull?.owner as? IrClass
            val baseType = classifier?.hintParameterType(context)
                ?: continue // shouldn't happen — extractSlotsFromDefinition guarded this
            val paramType = if (slot.isNullable) baseType.makeNullable() else baseType
            val hintParam = context.irFactory.createValueParameter(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.DEFINED,
                name = Name.identifier(slot.name),
                type = paramType,
                isAssignable = false,
                symbol = IrValueParameterSymbolImpl(),
                kind = IrParameterKind.Regular,
                varargElementType = null,
                isCrossinline = false,
                isNoinline = false,
                isHidden = false,
            )
            hintParam.parent = function
            params.add(hintParam)
        }

        if (params.isEmpty()) return

        function.parameters = params
        function.body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET, emptyList())
        function.addDeprecatedHiddenAnnotation(context)

        // Synthetic IrFile (one per target — keeps file names deterministic and unique)
        val firModuleData = run {
            val src = targetClass.metadata
            when (src) {
                is FirMetadataSource.Class -> src.fir.moduleData
                is FirMetadataSource.Function -> src.fir.moduleData
                is FirMetadataSource.File -> src.fir.moduleData
                else -> moduleFragment.files.firstNotNullOfOrNull { f ->
                    when (val m = f.metadata) {
                        is FirMetadataSource.File -> m.fir.moduleData
                        is FirMetadataSource.Class -> m.fir.moduleData
                        else -> null
                    }
                }
            }
        }
        if (firModuleData == null) {
            KoinPluginLogger.debug { "  InjectedParam hint: no FIR module data for $targetFqName — skipping" }
            return
        }

        // Module-disambiguating prefix so two Gradle modules emitting an InjectedParam hint
        // for the same target type don't produce identical class names at dex merge time.
        val modulePrefix = HintFilePrefix.of(firModuleData.name.asString())
        val fileName = "${modulePrefix}${KoinPluginConstants.INJECTED_PARAMS_HINT_PREFIX}${flat}.kt"
        val firFile = buildFile {
            moduleData = firModuleData
            origin = FirDeclarationOrigin.Synthetic.PluginFile
            packageDirective = buildPackageDirective { packageFqName = hintsPackage }
            name = fileName
        }
        // Anchor the synthetic hint file on a stable path from the current compile unit
        // (see issue #32). Priority: DSL registration site → target class source → sorted
        // first file in module. Mirrors [DslHintGenerator.generateDslDefinitionHints].
        val registrationSourceFile = (def as? Definition.DslDef)?.registrationSourceFile
        val targetClassFile = try {
            val entry = targetClass.fileEntry
            if (entry.name.contains("/") || entry.name.contains("\\")) entry.name else null
        } catch (_: NotImplementedError) {
            null
        }
        val basePath = registrationSourceFile?.fileEntry?.name
            ?: targetClassFile
            ?: moduleFragment.files.minByOrNull { it.fileEntry.name }?.fileEntry?.name
            ?: "/synthetic"
        val fakeNewPath = Path(basePath).parent.resolve(fileName)

        val hintFile = IrFileImpl(
            fileEntry = NaiveSourceBasedFileEntryImpl(fakeNewPath.absolutePathString()),
            packageFragmentDescriptor = EmptyPackageFragmentDescriptor(
                moduleFragment.descriptor,
                hintsPackage,
            ),
            module = moduleFragment,
        ).also { it.metadata = FirMetadataSource.File(firFile) }

        moduleFragment.addFile(hintFile)
        hintFile.addChild(function)
        context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)

        KoinPluginLogger.debug {
            "  Generated @InjectedParam hint: ${hintName.asString()} -> $targetFqName (${slots.size} slot(s))"
        }
    }

    /**
     * Discover the cross-module hint for [targetFqName] via `referenceFunctions` and rebuild
     * the slot list from its signature. Returns `null` when the hint is absent.
     */
    private fun discoverCrossModuleSlots(targetFqName: String): List<InjectedParamSlot>? {
        val flat = KoinPluginConstants.flattenFqNameForHint(targetFqName)
        val hintName = Name.identifier("${KoinPluginConstants.INJECTED_PARAMS_HINT_PREFIX}$flat")
        val symbols = context.referenceFunctions(
            CallableId(FqName(KoinPluginConstants.HINTS_PACKAGE), hintName)
        )
        val hintFunc = symbols.firstOrNull()?.owner ?: return null
        val slots = mutableListOf<InjectedParamSlot>()
        for (p in hintFunc.regularParameters) {
            val classifier = (p.type.classifierOrNull as? IrClassSymbol)?.owner
            val typeFqName = classifier?.fqNameWhenAvailable?.asString() ?: return null
            slots.add(
                InjectedParamSlot(
                    name = p.name.asString(),
                    typeFqName = typeFqName,
                    isNullable = p.type.isMarkedNullable(),
                )
            )
        }
        return slots.ifEmpty { null }
    }
}
