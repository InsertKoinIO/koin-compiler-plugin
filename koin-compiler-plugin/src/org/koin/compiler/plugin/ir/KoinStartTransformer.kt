package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.koin.compiler.plugin.KoinPluginConstants
import org.koin.compiler.plugin.KoinDiagnostic
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Transforms calls to startKoin<T>(), koinApplication<T>(), koinConfiguration<T>(), and
 * KoinApplication.withConfiguration<T>() to inject modules.
 *
 * Input:
 * ```kotlin
 * @KoinApplication(modules = [MyModule::class])
 * object MyApp
 *
 * startKoin<MyApp> {
 *     printLogger()
 * }
 * // or
 * koinApplication<MyApp> { }
 * // or
 * koinConfiguration<MyApp>()
 * // or
 * koinApplication { }.withConfiguration<MyApp>()
 * ```
 *
 * Output:
 * ```kotlin
 * startKoinWith(listOf(MyModule().module())) {
 *     printLogger()
 * }
 * // or
 * koinApplicationWith(listOf(MyModule().module())) { }
 * // or
 * koinConfigurationWith(listOf(MyModule().module()))
 * // or
 * koinApplication { }.withConfigurationWith(listOf(MyModule().module()))
 * ```
 */
/**
 * Internal reification of a detected entry point (A3 §4). Purely a plugin-side model built from the
 * EXISTING Koin entry-point APIs — no public/Koin-core API change. Every entry point is a self-consistent
 * ROOT (its graph resolves within itself); DYNAMIC = module set not statically resolvable → disclose.
 */
enum class EntryKind { START_KOIN, KOIN_APPLICATION, KOIN_CONFIGURATION, WITH_CONFIGURATION }
enum class EntryClassification { ROOT, DYNAMIC }

/**
 * Result of walking an entry-point lambda for `modules(...)` calls: the statically-resolvable module
 * classes, plus whether ANY argument was non-static (conditional / spread / variable) — which makes
 * the assembled graph unverifiable at compile time (→ [EntryClassification.DYNAMIC], KOIN-W003).
 */
data class LambdaModules(val classes: List<IrClass>, val dynamic: Boolean)
data class EntryPoint(
    val kind: EntryKind,
    // Mutable: the plugin-stub path reifies the entry as ROOT, then reclassifies to DYNAMIC once the
    // lambda walk finds a non-static modules(...) argument (assigned once, single-threaded IR walk).
    var classification: EntryClassification,
    // Mutable so the entry point can be reified at detection time and its closure filled in once
    // resolved a few lines later in the same visitCall (Gate-1: each root resolves its OWN
    // modules + includes + @ComponentScan/@Configuration set). Assigned exactly once, on the
    // single-threaded IR walk, immediately after construction.
    var resolvedModules: List<IrClass>,
    val origin: SourceOrigin?,
    // Display name passed to the verifier (leaks into KOIN-D001's `module = …`). Typed roots use
    // the app class simple name; untyped roots use a fixed `startKoin { … }`-style label. Assigned
    // alongside resolvedModules so [verifyEntryPoints] reproduces the exact pre-refactor message.
    var appName: String = "",
)

@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class KoinStartTransformer(
    private val context: IrPluginContext,
    private val moduleFragment: IrModuleFragment,
    private val annotationProcessor: KoinAnnotationProcessor? = null,
    private val safetyValidator: CompileSafetyValidator? = null,
    private val lookupTracker: LookupTracker? = null,
    private val expectActualTracker: ExpectActualTracker? = null,
    private val dslDefinitions: List<Definition> = emptyList()
) : IrElementTransformerVoid() {

    private var currentFile: IrFile? = null

    /**
     * Reified entry points detected in this compilation (A3 §4). Each ROOT carries its own resolved
     * module closure; [verifyEntryPoints] routes them all through the single graph verifier.
     */
    val entryPoints = mutableListOf<EntryPoint>()

    /** True if any entry point (startKoin / koinApplication / koinConfiguration, generic or not) was found. */
    val hasKoinEntryPoint: Boolean get() = entryPoints.isNotEmpty()

    /**
     * True if this compilation owns an AUTHORITATIVE application graph — i.e. an entry point that
     * installs the global Koin context and is therefore expected to be able to resolve everything
     * the whole application asks for.
     *
     * Deliberately narrower than [hasKoinEntryPoint], because "is there an entry point here" and
     * "is this the application" are different questions and only the second licenses judging OTHER
     * modules:
     *
     *  - `startKoin { }` / `@KoinApplication` install the global context → authoritative.
     *  - `koinApplication { }` launches an ISOLATED context. It is a perfectly real entry point and
     *    its own graph is validated (Phase 3.1) — but it is the idiom you reach for when you want a
     *    deliberately partial graph: a Compose preview, a test fixture, a KMP helper holding its own
     *    Koin instance. It does not own the application, so a call-site hint deferred by some other
     *    module is none of its business.
     *  - `koinConfiguration { }` / `withConfiguration { }` are configuration fragments, same
     *    reasoning.
     *
     * Gating Phase 3.6 on [hasKoinEntryPoint] made any library module containing a
     * `koinApplication { }` hard-error KOIN-D003 for every unresolved call-site hint on its
     * classpath — types it neither owns nor should provide. Regression test:
     * `testData/crossmodule/cross_module_koinapplication_library_ok.kt`.
     */
    val ownsAuthoritativeGraph: Boolean
        get() = entryPoints.any { it.kind == EntryKind.START_KOIN }

    /**
     * A3 §4 — the SINGLE graph-verification pass. Runs over every reified [EntryPoint] AFTER the IR
     * walk (called from [KoinIrExtension] Phase 3, before Phase 3.1) instead of validating inline
     * mid-visit. Behavior-preserving: each ROOT with a resolved closure is validated with the exact
     * (appName, modules, …) arguments the inline calls used, so KOIN-D001 output is unchanged.
     *
     * Roots with an empty closure (today: the real koin-core `startKoin{}` / `koinConfiguration{}`
     * path, still flag-only) are skipped here — their resolution is wired in a later Gate-1 sub-step.
     */
    fun verifyEntryPoints() {
        val validator = safetyValidator ?: return
        val processor = annotationProcessor ?: return
        KoinPluginLogger.debug { "── A3 verify: ${entryPoints.size} reified entry point(s) ──" }
        for (ep in entryPoints) {
            if (ep.resolvedModules.isEmpty()) {
                KoinPluginLogger.debug { "  skip ${ep.kind} @ ${ep.origin} — no resolved modules (closure resolution pending)" }
                continue
            }
            KoinPluginLogger.debug {
                "  verify ${ep.kind} '${ep.appName}' [${ep.classification}] — ${ep.resolvedModules.size} module(s) @ ${ep.origin}"
            }
            validator.validateFullGraph(
                ep.appName,
                ep.resolvedModules,
                processor.collectedModuleClasses,
                processor::getDefinitionsForModule,
                processor::getDefinitionsForDependencyModule,
                dslDefinitions
            )
        }
    }

    override fun visitFile(declaration: IrFile): IrFile {
        currentFile = declaration
        return super.visitFile(declaration)
    }

    // Koin types
    private val koinModuleClassId = ClassId.topLevel(FqName("org.koin.core.module.Module"))

    // Annotation FQName
    private val moduleFqName = FqName("org.koin.core.annotation.Module")

    // Hint package for cross-module discovery (label-specific function names)
    private val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

    // Module function resolver (multi-strategy lookup for module() extension functions)
    private val moduleFunctionResolver = ModuleFunctionResolver(context, moduleFragment)

    // Per-compile caches. The annotation walk and the full-IR scan in
    // `discoverLocalConfigurationModules` are both invariant for a given (appClass, labels)
    // and frequently re-asked: every typed `startKoin<T>()` / `koinApplication<T>()` site
    // hits them, and test-apps routinely has ~9 such entry points per compile.
    //
    // Keyed by FqName (not IrClass identity) so the same logical app class resolved through
    // two symbol paths still hits the cache. Falls back to a stable synthetic string when
    // FqName is unavailable so the cache never collapses unrelated anonymous classes.
    private val moduleClassesByApp = mutableMapOf<String, List<IrClass>>()
    private val configModulesByLabels = mutableMapOf<Set<String>, List<IrClass>>()

    private fun appClassCacheKey(appClass: IrClass): String =
        appClass.fqNameWhenAvailable?.asString() ?: "<anon>@${System.identityHashCode(appClass)}"

    /** Best-effort source origin of a call site (for the reified EntryPoint / debug logs). */
    private fun originOf(expression: IrCall): SourceOrigin {
        val fe = currentFile?.fileEntry
        val line = fe?.let { runCatching { it.getLineNumber(expression.startOffset) + 1 }.getOrNull() }
        return SourceOrigin(moduleFqName = null, filePath = fe?.name, line = line)
    }

    /**
     * A3 §4 — disclose a DYNAMIC entry point (KOIN-W003): its module set is computed at runtime
     * (conditional / spread / variable), so the assembled graph can't be verified at compile time.
     * Emitting this warning keeps the "unverifiable" state visible rather than letting a green build
     * imply a guarantee it didn't make (see KoinDiagnostic.UnverifiableDynamicGraph).
     */
    private fun discloseDynamicEntryPoint(entryLabel: String, expression: IrCall) {
        val o = originOf(expression)
        val originStr = o.filePath?.let { "${it.substringAfterLast('/')}:${o.line ?: "?"}" }
        KoinPluginLogger.report(KoinDiagnostic.UnverifiableDynamicGraph(entryLabel, originStr))
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        val calleeFqName = callee.fqNameWhenAvailable

        // Check if this is our generic stub functions: startKoin<T>(), koinApplication<T>(), koinConfiguration<T>(),
        // or KoinApplication.withConfiguration<T>()
        // These get transformed to startKoinWith(modules, lambda), koinApplicationWith(modules, lambda),
        // koinConfigurationWith(modules), and withConfigurationWith(modules, lambda)
        val fqNameStr = calleeFqName?.asString()

        // Detect non-generic startKoin { } or koinApplication { } (entry point signal).
        // `org.koin.dsl.koinConfiguration` is koin-core's real function used by the Compose
        // `KoinApplication(configuration = koinConfiguration { modules(...) })` entry (issue #38);
        // recognizing it flips on the DSL-graph full validation (Phase 3.1) for that entry path.
        // We only mark it as an entry point here — we do NOT rewrite the call, so koin-core's
        // koinConfiguration behaves normally at runtime.
        // Real koin-core entry points. These are NOT rewritten (koin-core runs them normally at
        // runtime) — we only reify them as ROOTs and resolve their module closure so the A3 verifier
        // (verifyEntryPoints) validates the assembled graph at the root, exactly like the plugin-stub
        // and typed @KoinApplication forms.
        //   - org.koin.core.context.startKoin / GlobalContext.startKoin / KoinApplication.Companion.init
        //   - org.koin.dsl.koinApplication  (KMP-style `koinApplication { modules(...) }.koin`)
        //   - org.koin.dsl.koinConfiguration (Compose `KoinApplication(configuration = koinConfiguration { … })`, #38)
        // Each is a SEALED self-consistent root (its graph must resolve within its own module set).
        if (fqNameStr == "org.koin.core.context.startKoin" ||
            fqNameStr == "org.koin.core.context.GlobalContext.startKoin" ||
            fqNameStr == "org.koin.core.KoinApplication.Companion.init" ||
            fqNameStr == "org.koin.dsl.koinApplication" ||
            fqNameStr == "org.koin.dsl.koinConfiguration") {
            val kind = when (fqNameStr) {
                "org.koin.dsl.koinConfiguration" -> EntryKind.KOIN_CONFIGURATION
                "org.koin.dsl.koinApplication" -> EntryKind.KOIN_APPLICATION
                else -> EntryKind.START_KOIN
            }
            val label = when (kind) {
                EntryKind.KOIN_CONFIGURATION -> "koinConfiguration { … }"
                EntryKind.KOIN_APPLICATION -> "koinApplication { … }"
                else -> "startKoin { … }"
            }
            // Resolve this root's own closure by walking its trailing lambda for plugin-stub
            // modules(vararg KClass) calls — the same walk the untyped-stub path uses. Without this,
            // the root stays flag-only and A3 never sees its modules.
            val lambdaModules = collectModuleClassesFromLambda(expression)
            val classification = if (lambdaModules.dynamic) EntryClassification.DYNAMIC else EntryClassification.ROOT
            entryPoints.add(EntryPoint(kind, classification, lambdaModules.classes, originOf(expression), label))
            val entryFile = currentFile
            for (moduleClass in lambdaModules.classes) {
                trackClassLookup(lookupTracker, entryFile, moduleClass)
                linkDeclarationsForIC(expectActualTracker, entryFile, moduleClass)
            }
            if (lambdaModules.dynamic) discloseDynamicEntryPoint(label, expression)
            KoinPluginLogger.debug {
                "  entry point (koin-core): $kind [$classification] @ ${originOf(expression)} — ${lambdaModules.classes.size} module(s): " +
                    lambdaModules.classes.joinToString(", ") { it.fqNameWhenAvailable?.asString() ?: it.name.asString() }
            }
        }

        val isStartKoin = fqNameStr == "org.koin.plugin.module.dsl.startKoin"
        val isKoinApplication = fqNameStr == "org.koin.plugin.module.dsl.koinApplication"
        val isKoinConfiguration = fqNameStr == "org.koin.plugin.module.dsl.koinConfiguration"
        val isWithConfiguration = fqNameStr == "org.koin.plugin.module.dsl.withConfiguration" &&
            callee.extensionReceiverParam?.type?.classFqName?.asString() == "org.koin.core.KoinApplication"

        // KoinApplication.module<T>() — load a single @Module class
        val isModuleLoad = fqNameStr == "org.koin.plugin.module.dsl.module" &&
            callee.extensionReceiverParam?.type?.classFqName?.asString() == "org.koin.core.KoinApplication"
        if (isModuleLoad) {
            return transformModuleLoad(expression, callee)
        }

        // KoinApplication.modules(vararg KClass) — load multiple @Module classes
        val isModulesLoad = fqNameStr == "org.koin.plugin.module.dsl.modules" &&
            callee.extensionReceiverParam?.type?.classFqName?.asString() == "org.koin.core.KoinApplication" &&
            callee.regularParameters.size == 1 &&
            callee.regularParameters[0].varargElementType != null
        if (isModulesLoad) {
            return transformModulesLoad(expression)
        }

        if (!isStartKoin && !isKoinApplication && !isKoinConfiguration && !isWithConfiguration) {
            return super.visitCall(expression)
        }

        // Reify the generic-stub entry point (resolvedModules filled at Gate-1 wiring).
        val entryKind = when {
            isStartKoin -> EntryKind.START_KOIN
            isKoinApplication -> EntryKind.KOIN_APPLICATION
            isKoinConfiguration -> EntryKind.KOIN_CONFIGURATION
            isWithConfiguration -> EntryKind.WITH_CONFIGURATION
            else -> EntryKind.START_KOIN
        }
        // Reify the entry point now; resolvedModules is populated below once we resolve this
        // root's own closure (typed → @KoinApplication annotation; untyped → lambda modules(...) walk).
        val entryPoint = EntryPoint(entryKind, EntryClassification.ROOT, emptyList(), originOf(expression))
        entryPoints.add(entryPoint)
        KoinPluginLogger.debug { "  entry point (stub): $entryKind @ ${originOf(expression)}" }

        // Untyped variants — `startKoin { modules(A::class) }`, `koinApplication { modules(...) }`,
        // and especially the Compose Composable-friendly `koinConfiguration { modules(...) }`
        // (issue #38 / KTZ-4037) — don't have a `<T>` type parameter to drive `@KoinApplication`
        // discovery, but they DO carry the module list directly inside the trailing lambda. Walk
        // the lambda for `KoinApplication.modules(vararg KClass)` calls and route the result
        // through A3 so the bare-config entry point gets the same full-graph validation as the
        // typed entry point.
        if (callee.typeParameters.isEmpty()) {
            val lambdaModuleClasses = collectModuleClassesFromLambda(expression)
            val entryName = when {
                isStartKoin -> "startKoin { … }"
                isKoinApplication -> "koinApplication { … }"
                isKoinConfiguration -> "koinConfiguration { … }"
                isWithConfiguration -> "withConfiguration { … }"
                else -> "<unknown-entry>"
            }
            // Reify this root's own closure (Gate-1). Verification is now centralized in
            // [verifyEntryPoints], which runs over `entryPoints` after this IR walk.
            entryPoint.resolvedModules = lambdaModuleClasses.classes
            entryPoint.appName = entryName
            if (lambdaModuleClasses.dynamic) {
                entryPoint.classification = EntryClassification.DYNAMIC
                discloseDynamicEntryPoint(entryName, expression)
            }
            KoinPluginLogger.debug {
                "  -> resolved closure ($entryName, untyped) [${entryPoint.classification}]: ${lambdaModuleClasses.classes.size} module(s): " +
                    lambdaModuleClasses.classes.joinToString(", ") { it.fqNameWhenAvailable?.asString() ?: it.name.asString() }
            }
            // IC: entry-point file depends on each discovered module class. Guard kept identical to
            // the pre-refactor validation guard so incremental edges are recorded exactly as before.
            if (lambdaModuleClasses.classes.isNotEmpty() && safetyValidator != null && annotationProcessor != null) {
                val startKoinFile = currentFile
                for (moduleClass in lambdaModuleClasses.classes) {
                    trackClassLookup(lookupTracker, startKoinFile, moduleClass)
                    linkDeclarationsForIC(expectActualTracker, startKoinFile, moduleClass)
                }
            }
            return super.visitCall(expression)
        }

        // Get the type argument T from startKoin<T>
        val typeArg = expression.getTypeArgumentCompat(0) ?: return super.visitCall(expression)
        val appClass = (typeArg.classifierOrNull as? IrClassSymbol)?.owner
            ?: return super.visitCall(expression)

        // Get modules from @KoinApplication(modules = [...]) annotation
        val moduleClasses = extractModulesFromKoinApplicationAnnotation(appClass)

        // Reify this root's own closure (Gate-1). Verification is centralized in [verifyEntryPoints].
        entryPoint.resolvedModules = moduleClasses
        entryPoint.appName = appClass.name.asString()
        KoinPluginLogger.debug {
            "  -> resolved closure (${entryPoint.appName}, typed <${appClass.name.asString()}>): ${moduleClasses.size} module(s): " +
                moduleClasses.joinToString(", ") { it.fqNameWhenAvailable?.asString() ?: it.name.asString() }
        }

        // IC: startKoin file depends on each discovered module class
        val startKoinFile = currentFile
        for (moduleClass in moduleClasses) {
            trackClassLookup(lookupTracker, startKoinFile, moduleClass)
            linkDeclarationsForIC(expectActualTracker, startKoinFile, moduleClass)
        }

        // Log interception (guard to avoid precomputation when logging is disabled)
        if (KoinPluginLogger.userLogsEnabled) {
            val appClassName = appClass.fqNameWhenAvailable?.asString() ?: "Unknown"
            val functionDisplayName = when {
                isStartKoin -> "startKoin<$appClassName>()"
                isKoinApplication -> "koinApplication<$appClassName>()"
                isKoinConfiguration -> "koinConfiguration<$appClassName>()"
                else -> "KoinApplication.withConfiguration<$appClassName>()"
            }
            KoinPluginLogger.user { "Intercepting $functionDisplayName" }
            if (moduleClasses.isNotEmpty()) {
                val moduleNames = moduleClasses.mapNotNull { it.fqNameWhenAvailable?.asString() }.joinToString(", ")
                KoinPluginLogger.user { "  -> Injecting modules: $moduleNames" }
            } else {
                KoinPluginLogger.user { "  -> No modules to inject" }
            }
        }

        // Get the lambda argument (first argument in the generic version, may be null if default)
        val lambdaArg = expression.getRegularArgument(0)

        // Find the implementation function:
        // - startKoinWith(modules, lambda) for startKoin<T>()
        // - koinApplicationWith(modules, lambda) for koinApplication<T>()
        // - koinConfigurationWith(modules, lambda) for koinConfiguration<T>()
        // - withConfigurationWith(modules, lambda) for KoinApplication.withConfiguration<T>()
        val implFunctionName = when {
            isStartKoin -> "startKoinWith"
            isKoinApplication -> "koinApplicationWith"
            isKoinConfiguration -> "koinConfigurationWith"
            else -> "withConfigurationWith"
        }
        val implFunction = context.referenceFunctions(
            CallableId(FqName("org.koin.plugin.module.dsl"), Name.identifier(implFunctionName))
        ).firstOrNull { func ->
            func.owner.typeParameters.isEmpty() &&
            func.owner.regularParameters.size == 2
        }?.owner ?: return super.visitCall(expression)

        val koinModuleClass = context.referenceClass(koinModuleClassId)?.owner
            ?: return super.visitCall(expression)

        val builder = DeclarationIrBuilder(context, expression.symbol, expression.startOffset, expression.endOffset)

        // Build module expressions: listOf(Module1().module(), Module2().module(), ...)
        val moduleExpressions = moduleClasses.mapNotNull { moduleClass ->
            moduleFunctionResolver.buildModuleGetCall(moduleClass, builder)
        }

        // Find listOf function
        val listOfFunction = context.referenceFunctions(
            CallableId(FqName("kotlin.collections"), Name.identifier("listOf"))
        ).firstOrNull { func ->
            func.owner.regularParameters.size == 1 &&
            func.owner.regularParameters[0].varargElementType != null
        }?.owner ?: return super.visitCall(expression)

        // Create listOf(module1, module2, ...) or emptyList()
        val modulesListArg = if (moduleExpressions.isNotEmpty()) {
            builder.irCall(listOfFunction.symbol).apply {
                putTypeArgumentCompat(0, koinModuleClass.defaultType)
                putRegularArgument(0, builder.irVararg(
                    koinModuleClass.defaultType,
                    moduleExpressions
                ))
            }
        } else {
            // Create emptyList<Module>()
            val emptyListFunction = context.referenceFunctions(
                CallableId(FqName("kotlin.collections"), Name.identifier("emptyList"))
            ).firstOrNull()?.owner ?: return super.visitCall(expression)

            builder.irCall(emptyListFunction.symbol).apply {
                putTypeArgumentCompat(0, koinModuleClass.defaultType)
            }
        }

        // Create call to implementation: startKoinWith(listOf(...), lambda)
        // For withConfiguration, we also need to pass the extension receiver (KoinApplication instance)
        return builder.irCall(implFunction.symbol).apply {
            if (isWithConfiguration) {
                // withConfigurationWith is an extension on KoinApplication, preserve the receiver
                setExtensionReceiverArgument(expression.extensionReceiverArgument)
            }
            putRegularArgument(0, modulesListArg)
            putRegularArgument(1, lambdaArg)
        }
    }

    /**
     * Extract module classes from @KoinApplication annotation.
     *
     * Combines:
     * 1. Explicit modules from @KoinApplication(modules = [MyModule::class, ...])
     * 2. Auto-discovered @Configuration modules filtered by configuration labels
     *
     * Configuration label filtering:
     * - @KoinApplication(configurations = ["test"]) → only @Configuration("test") modules
     * - @KoinApplication() or @KoinApplication(configurations = []) → only @Configuration() (default) modules
     */
    private fun extractModulesFromKoinApplicationAnnotation(appClass: IrClass): List<IrClass> =
        moduleClassesByApp.getOrPut(appClassCacheKey(appClass)) { computeModuleClasses(appClass) }

    private fun computeModuleClasses(appClass: IrClass): List<IrClass> {
        val explicitModules = extractExplicitModules(appClass)
        val configurationLabels = extractAppRequestedConfigurationLabels(appClass)

        KoinPluginLogger.debug { "  -> Configuration labels from @KoinApplication: $configurationLabels" }

        // Discover modules filtered by configuration labels
        val discoveredModules = discoverConfigurationModules(configurationLabels)

        // Koin is last-wins at runtime, so load order determines override precedence.
        //
        // Rule (#2402): auto-discovered @Configuration modules first, explicit
        // @KoinApplication(modules = [...]) last. That way an app module listing a
        // feature override wins over the discovered dependency, which matches the
        // typical intent of "the app customises the libraries, not the other way round".
        //
        // Within each half we preserve the user's declaration order so they can still
        // control fine-grained order via an explicit list (modules = [A, B, C] loads
        // A then B then C, and C wins among those three).
        //
        // Dedupe discovered AGAINST explicit (not the other way round) so a module the
        // user re-declares in the explicit list keeps its explicit position — and
        // therefore its explicit override priority.
        val explicitFqNames = explicitModules.mapNotNull { it.fqNameWhenAvailable }.toSet()
        val uniqueDiscovered = discoveredModules.filterNot { it.fqNameWhenAvailable in explicitFqNames }

        return uniqueDiscovered + explicitModules
    }

    /**
     * Extract configuration labels from @KoinApplication annotation.
     * @KoinApplication(configurations = ["test", "prod"]) -> ["test", "prod"]
     * @KoinApplication() or @KoinApplication(configurations = []) -> ["default"]
     */
    private fun extractAppRequestedConfigurationLabels(appClass: IrClass): List<String> {
        val koinAppAnnotation = appClass.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == "org.koin.core.annotation.KoinApplication"
        } ?: return listOf(KoinPluginConstants.DEFAULT_LABEL)

        // Look up configurations by name first, then fall back to positional index 0
        val configurationsArg = koinAppAnnotation.getValueArgument(Name.identifier("configurations"))
            ?: koinAppAnnotation.getRegularArgument(0)

        val labels = mutableListOf<String>()
        when (configurationsArg) {
            is IrVararg -> {
                for (element in configurationsArg.elements) {
                    when (element) {
                        is IrConst -> {
                            val value = element.value
                            if (value is String) {
                                labels.add(value)
                            }
                        }
                        else -> {}
                    }
                }
            }
            is IrConst -> {
                val value = configurationsArg.value
                if (value is String) {
                    labels.add(value)
                }
            }
            else -> {}
        }

        // Default to "default" label if no labels specified
        return labels.ifEmpty { listOf(KoinPluginConstants.DEFAULT_LABEL) }
    }

    /**
     * Discover @Configuration modules filtered by configuration labels.
     * Combines local modules and modules from hint functions.
     */
    private fun discoverConfigurationModules(labels: List<String>): List<IrClass> =
        configModulesByLabels.getOrPut(labels.toSet()) {
            val localModules = discoverLocalConfigurationModules(labels)
            val hintModules = discoverModulesFromHints(labels)
            (localModules + hintModules).distinctBy { it.fqNameWhenAvailable }
        }

    /**
     * Extract explicitly listed modules from @KoinApplication(modules = [...])
     */
    private fun extractExplicitModules(appClass: IrClass): List<IrClass> {
        val koinAppAnnotation = appClass.annotations.firstOrNull { annotation ->
            annotation.type.classFqName?.asString() == "org.koin.core.annotation.KoinApplication"
        } ?: return emptyList()

        // Look up modules by name first, then fall back to positional index 1
        val modulesArg = koinAppAnnotation.getValueArgument(Name.identifier("modules"))
            ?: koinAppAnnotation.getRegularArgument(1)
            ?: return emptyList()

        // The argument should be a vararg/array of KClass references
        return when (modulesArg) {
            is IrVararg -> modulesArg.elements.mapNotNull { element ->
                when (element) {
                    is IrClassReference -> (element.classType.classifierOrNull as? IrClassSymbol)?.owner
                    is IrExpression -> extractClassFromKClassExpression(element)
                    else -> null
                }
            }
            is IrClassReference -> listOfNotNull((modulesArg.classType.classifierOrNull as? IrClassSymbol)?.owner)
            else -> emptyList()
        }.filter { it.fqNameWhenAvailable?.asString() != "kotlin.Unit" } // Filter out default Unit::class
    }

    /**
     * Discover @Configuration modules in the current compilation unit.
     * Filters by configuration labels — a module is included if it has ANY of the requested labels.
     *
     * Reuses [KoinAnnotationProcessor.collectedModuleClasses] from Phase 1 instead of doing a
     * second `moduleFragment.acceptChildrenVoid` pass. The annotation processor already walked
     * every IrClass in this fragment and recorded every `@Module`; re-walking solely to filter
     * for `@Configuration` doubled the cost for what amounted to a list filter. The Phase 1
     * collection is invariant for the rest of IR generation, so consuming it here is safe.
     *
     * Fallback: when no annotation processor is wired (bare-CLI / test paths that instantiate
     * KoinStartTransformer without one), do the old IR walk so behavior stays correct.
     *
     * @param labels Configuration labels to filter by
     */
    private fun discoverLocalConfigurationModules(labels: List<String>): List<IrClass> {
        val processor = annotationProcessor
        val modules: List<IrClass> = if (processor != null) {
            processor.collectedModuleClasses
                .map { it.irClass }
                .filter { hasConfigurationWithMatchingLabels(it, labels) }
        } else {
            val collected = mutableListOf<IrClass>()
            moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitClass(declaration: IrClass) {
                    if (hasConfigurationWithMatchingLabels(declaration, labels)) {
                        collected.add(declaration)
                    }
                    super.visitClass(declaration)
                }
            })
            collected
        }

        KoinPluginLogger.debug { "  -> Found ${modules.size} local @Configuration modules matching labels $labels" }
        return modules
    }

    /**
     * Discover @Configuration modules from hint functions (local + dependencies).
     * Queries configuration_<label> hint functions via context.referenceFunctions(),
     * which sees both local FIR-generated hints and dependency hints from klib/JAR metadata.
     *
     * @param labels Configuration labels to filter by
     */
    private fun discoverModulesFromHints(labels: List<String>): List<IrClass> {
        val modules = mutableListOf<IrClass>()
        // Dedup by FqName rather than IrClass identity — the same logical module reached via
        // two label hints can resolve to distinct IrClass instances (external stub vs. local),
        // and `moduleClass !in modules` (list contains, identity) would miss that.
        val seenFqNames = mutableSetOf<String>()

        try {
            for (label in labels) {
                val callableId = CallableId(hintsPackage, KoinModuleFirGenerator.hintFunctionNameForLabel(label))
                val hintFunctions = context.referenceFunctions(callableId)

                KoinPluginLogger.debug { "  -> Hint query for label '$label': ${hintFunctions.count()} functions" }

                for (hintFuncSymbol in hintFunctions) {
                    val hintFunc = hintFuncSymbol.owner
                    val paramType = hintFunc.regularParameters.firstOrNull()?.type
                    val moduleClass = (paramType?.classifierOrNull as? IrClassSymbol)?.owner ?: continue
                    val fqName = moduleClass.fqNameWhenAvailable?.asString()
                        ?: "<anon>@${System.identityHashCode(moduleClass)}"
                    if (seenFqNames.add(fqName)) {
                        KoinPluginLogger.debug { "  -> Found hint module: $fqName (label=$label)" }
                        modules.add(moduleClass)
                    }
                }
            }
        } catch (e: Exception) {
            KoinPluginLogger.debug { "  -> Error during hint discovery: ${e.message}" }
        }

        return modules
    }

    /**
     * Check if a class has @Module and @Configuration annotations with matching labels.
     * A module matches if it has ANY of the requested labels.
     *
     * Bug fixed (1.1.0): this used to call extractAppRequestedConfigurationLabels below — which
     * reads @KoinApplication(configurations=[...]), meant for the ENTRY-POINT class, not the
     * module being checked here. Because module classes never carry @KoinApplication, that call
     * always fell through to the "annotation absent" fallback (["default"]), making ANY @Module
     * class in the same Gradle module as a default-labeled entry point match "default" regardless
     * of whether it actually had @Configuration or was ever included by anyone — a silent
     * over-broad reachability false-negative, newly consequential now that A2 is gone and A3 is
     * the sole verifier. extractModuleConfigurationLabels (ConfigurationUtils.kt) reads the
     * MODULE's own @Configuration annotation and returns an EMPTY list — no match — when absent.
     * The two extractors are deliberately named differently now so they can never shadow each
     * other again (the original bug was exactly this: two same-named functions, member resolution
     * silently picking the wrong one).
     */
    private fun hasConfigurationWithMatchingLabels(declaration: IrClass, labels: List<String>): Boolean {
        val hasModule = declaration.annotations.any {
            it.type.classFqName?.asString() == moduleFqName.asString()
        }
        if (!hasModule) return false

        return extractModuleConfigurationLabels(declaration).any { it in labels }
    }

    /**
     * Extract class from KClass expression (e.g., MyClass::class wrapped in GetClass)
     */
    private fun extractClassFromKClassExpression(expression: IrExpression): IrClass? {
        return when (expression) {
            is IrClassReference -> (expression.classType.classifierOrNull as? IrClassSymbol)?.owner
            is IrGetClass -> (expression.argument.type.classifierOrNull as? IrClassSymbol)?.owner
            else -> null
        }
    }

    /**
     * Recursively walk a `startKoin { … }` / `koinApplication { … }` / `koinConfiguration { … }`
     * trailing lambda and return the union of every `IrClass` reachable via
     * `KoinApplication.modules(vararg KClass)` calls inside.
     *
     * The walk handles Compose's IR-plugin scaffolding the same way [KoinDSLTransformer.findParametersOfCall]
     * does — Compose Composables wrap user lambdas in `sourceInformationMarkerStart` +
     * `remember { ... }` + `IrVariable` initializers, so the literal `IrFunctionExpression`
     * argument shape can't be assumed. Descending through `IrCall` / `IrFunctionExpression` /
     * `IrFunctionReference` / `IrBlockBody` / `IrContainerExpression` / `IrReturn` /
     * `IrVariable` / `IrTypeOperatorCall` / `IrSetValue` cuts through the scaffolding
     * regardless of the exact shape.
     *
     * Used by the untyped-entry A3 path (KTZ-4037 / #38) — without a `<T>` type parameter we
     * can't get modules from `@KoinApplication(modules = [...])`, but the user's `modules(...)`
     * call inside the lambda gives us the same list at the IR level.
     */
    private fun collectModuleClassesFromLambda(call: IrCall): LambdaModules {
        val result = LinkedHashSet<IrClass>()
        // dynamic = at least one modules(...) argument is NOT a statically-resolvable KClass — a
        // conditional (`modules(if (x) A else B)`), a spread of a runtime list (`modules(*list)`),
        // or a variable. The loaded set then depends on runtime values, so the graph is unverifiable
        // at compile time for this root (A3 §4, DYNAMIC classification → KOIN-W003 disclosure).
        var dynamic = false
        // Identity-based visited set — IrFunctionReference resolves to a function whose body
        // may be revisited from elsewhere in the tree (Compose `remember` scaffolding, captured
        // lambdas, etc.). Without a guard, pathological IR with cycles would recurse forever
        // and benign cases would re-walk the same subtree once per visit.
        val visited = java.util.IdentityHashMap<IrElement, Unit>()

        fun visit(node: IrElement?) {
            if (node == null) return
            if (visited.put(node, Unit) != null) return
            if (node is IrCall) {
                val nc = node.symbol.owner
                val ncFq = nc.fqNameWhenAvailable?.asString()
                val isModulesCall = ncFq == "org.koin.plugin.module.dsl.modules" &&
                    nc.extensionReceiverParam?.type?.classFqName?.asString() == "org.koin.core.KoinApplication" &&
                    nc.regularParameters.size == 1 &&
                    nc.regularParameters[0].varargElementType != null
                if (isModulesCall) {
                    val varargArg = node.getRegularArgument(0) as? IrVararg
                    if (varargArg == null) {
                        // modules(...) present but the argument isn't a plain vararg (e.g. a spread
                        // of a runtime array) → the module set is not statically resolvable.
                        dynamic = true
                    } else {
                        varargArg.elements.forEach { element ->
                            val cls = when (element) {
                                is IrClassReference -> (element.classType.classifierOrNull as? IrClassSymbol)?.owner
                                is IrExpression -> extractClassFromKClassExpression(element)
                                else -> null // e.g. IrSpreadElement
                            }
                            if (cls != null) result.add(cls) else dynamic = true
                        }
                    }
                }
                for (i in 0 until node.regularArgumentsCount) visit(node.getRegularArgument(i))
                visit(node.dispatchReceiver)
                visit(node.extensionReceiverArgument)
                return
            }
            if (node is IrFunctionExpression) { visit(node.function.body); return }
            if (node is IrFunctionReference) { visit((node.symbol.owner as? IrSimpleFunction)?.body); return }
            if (node is IrBlockBody) { node.statements.forEach { visit(it) }; return }
            if (node is IrContainerExpression) { node.statements.forEach { visit(it) }; return }
            if (node is IrReturn) { visit(node.value); return }
            if (node is IrVariable) { visit(node.initializer); return }
            if (node is IrTypeOperatorCall) { visit(node.argument); return }
            if (node is IrSetValue) { visit(node.value); return }
        }

        for (i in 0 until call.regularArgumentsCount) visit(call.getRegularArgument(i))
        return LambdaModules(result.toList(), dynamic)
    }

    /**
     * Transform KoinApplication.module<T>() → KoinApplication.modules(T().module())
     *
     * Resolves the type argument T to a @Module class and calls the generated module() function.
     */
    private fun transformModuleLoad(expression: IrCall, callee: IrSimpleFunction): IrExpression {
        // Get type argument T
        val typeArg = expression.getTypeArgumentCompat(0) ?: return super.visitCall(expression)
        val moduleClass = (typeArg.classifierOrNull as? IrClassSymbol)?.owner
            ?: return super.visitCall(expression)

        KoinPluginLogger.user { "Intercepting KoinApplication.module<${moduleClass.name}>()" }

        val builder = DeclarationIrBuilder(context, expression.symbol, expression.startOffset, expression.endOffset)

        // Build: ModuleClass().module()
        val moduleExpression = moduleFunctionResolver.buildModuleGetCall(moduleClass, builder)
            ?: return super.visitCall(expression)

        // Find the real KoinApplication.modules(Module) function
        val koinAppClass = (expression.extensionReceiverArgument?.type?.classifierOrNull as? IrClassSymbol)?.owner
            ?: return super.visitCall(expression)

        val modulesFunction = koinAppClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { func ->
                func.name.asString() == "modules" &&
                func.regularParameters.size == 1 &&
                func.regularParameters[0].varargElementType != null
            }
            ?: return super.visitCall(expression)

        val koinModuleClass = context.referenceClass(koinModuleClassId)?.owner
            ?: return super.visitCall(expression)

        // Generate: this.modules(T().module())
        return builder.irCall(modulesFunction.symbol).apply {
            dispatchReceiver = expression.extensionReceiverArgument
            putRegularArgument(0, builder.irVararg(
                koinModuleClass.defaultType,
                listOf(moduleExpression)
            ))
        }
    }

    /**
     * Transform KoinApplication.modules(ModuleA::class, ModuleB::class)
     * → KoinApplication.modules(ModuleA().module(), ModuleB().module())
     *
     * Resolves each KClass argument to a @Module class and calls the generated module() function.
     */
    private fun transformModulesLoad(expression: IrCall): IrExpression {
        val varargArg = expression.getRegularArgument(0) as? IrVararg
            ?: return super.visitCall(expression)

        // Extract module classes from KClass references
        val moduleClasses = varargArg.elements.mapNotNull { element ->
            when (element) {
                is IrClassReference -> (element.classType.classifierOrNull as? IrClassSymbol)?.owner
                is IrExpression -> extractClassFromKClassExpression(element)
                else -> null
            }
        }

        if (moduleClasses.isEmpty()) return super.visitCall(expression)

        if (KoinPluginLogger.userLogsEnabled) {
            val names = moduleClasses.mapNotNull { it.fqNameWhenAvailable?.asString() }.joinToString(", ")
            KoinPluginLogger.user { "Intercepting KoinApplication.modules($names)" }
        }

        val builder = DeclarationIrBuilder(context, expression.symbol, expression.startOffset, expression.endOffset)

        // Build module expressions: ModuleA().module(), ModuleB().module(), ...
        val moduleExpressions = moduleClasses.mapNotNull { moduleClass ->
            moduleFunctionResolver.buildModuleGetCall(moduleClass, builder)
        }

        if (moduleExpressions.isEmpty()) return super.visitCall(expression)

        // Find the real KoinApplication.modules(vararg Module) function
        val koinAppClass = (expression.extensionReceiverArgument?.type?.classifierOrNull as? IrClassSymbol)?.owner
            ?: return super.visitCall(expression)

        val modulesFunction = koinAppClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { func ->
                func.name.asString() == "modules" &&
                func.regularParameters.size == 1 &&
                func.regularParameters[0].varargElementType != null
            }
            ?: return super.visitCall(expression)

        val koinModuleClass = context.referenceClass(koinModuleClassId)?.owner
            ?: return super.visitCall(expression)

        // Generate: this.modules(ModuleA().module(), ModuleB().module())
        return builder.irCall(modulesFunction.symbol).apply {
            dispatchReceiver = expression.extensionReceiverArgument
            putRegularArgument(0, builder.irVararg(
                koinModuleClass.defaultType,
                moduleExpressions
            ))
        }
    }

}
