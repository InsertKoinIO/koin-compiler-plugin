package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.koin.compiler.plugin.GeneratedResolutionCallRegistry
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinDiagnostic
import org.koin.compiler.plugin.KoinPluginLogger
import org.jetbrains.kotlin.ir.expressions.IrGetField

/**
 * Transforms Koin DSL calls — AND, in the SAME tree walk, collects the data A3 compile-safety
 * validation needs (KOIN-D00x/W00x diagnostics, resolved later in CallSiteValidator). One
 * `visitCall` pass serves both concerns deliberately: a second walk dedicated to safety would
 * re-derive exactly what codegen already computed on the way past (which function, which target
 * class, which qualifier), for no benefit.
 *
 * Because the two concerns share one walk, keep them visually separable by NAME and by comment,
 * not by physical separation:
 *  - `collect*` functions are SAFETY ONLY — they record into `_dslDefinitions`/
 *    `_pendingCallSites`/`_moduleIncludes`/etc. and never return a rewritten [IrExpression].
 *  - `handle*`/`build*`/`find*` functions are CODEGEN — they return the rewritten
 *    [IrExpression] (or resolve the real `build*` function to call). Several of these ALSO
 *    collect safety data inline, because they've already done the work safety needs (resolving
 *    the target class, the qualifier) — those spots carry an explicit `// SAFETY:` comment so
 *    they don't read as codegen.
 *  - `visitCall` itself is the one place both are unavoidably interleaved (safety must see a
 *    call before AND after codegen decides what to do with it) — its own inline `// SAFETY:` /
 *    `// CODEGEN:` / `// SHARED:` comments mark each phase as you read top to bottom.
 *
 * What it transforms:
 *
 * 1. Reified type parameter syntax (single<T>(), factory<T>(), etc.):
 *    single<MyClass>() -> single(MyClass::class, null) { MyClass(get(), get()) }
 *
 * 2. Constructor reference for create only:
 *    scope.create(::MyClass) -> MyClass(scope.get(), scope.get())
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class KoinDSLTransformer(
    private val context: IrPluginContext,
    private val lookupTracker: LookupTracker? = null,
    // Gate 3 (freshness): links a DSL call site's file to its target class's file for IC, so a
    // NEW declaration in the target's file (which LookupTracker can't see — it never existed
    // before) still invalidates this call site's compile task. Mirrors what
    // KoinAnnotationProcessor/KoinStartTransformer already do for annotation definitions and
    // entry-point modules; the DSL path was missing it.
    private val expectActualTracker: ExpectActualTracker? = null,
) : IrElementTransformerVoid() {

    private val unsafeDslChecksEnabled = KoinPluginLogger.unsafeDslChecksEnabled
    private val compileSafetyEnabled = KoinPluginLogger.compileSafetyEnabled
    private var currentFile: IrFile? = null

    // ── Collected DSL definitions (for A3 full-graph validation) ──
    private val _dslDefinitions = mutableListOf<Definition.DslDef>()
    val dslDefinitions: List<Definition.DslDef> get() = _dslDefinitions

    // ── Collected call-site validations (replaces KoinCallSiteValidator tree walk) ──
    private val _pendingCallSites = mutableListOf<PendingCallSiteValidation>()
    val collectedCallSites: List<PendingCallSiteValidation> get() = _pendingCallSites

    // ── Module loading graph (for DSL module loading validation) ──
    private val _moduleIncludes = mutableMapOf<String, MutableList<String>>()
    val moduleIncludes: Map<String, List<String>> get() = _moduleIncludes

    /**
     * Every `module { }` val seen in this compilation, whether or not it declares definitions or
     * `includes()` edges.
     *
     * Needed so hint generation can emit a file for a module that currently contributes NOTHING —
     * otherwise a module whose last definition/include is deleted drops out of the hint groups
     * entirely, no file is written, and the previous compile's class survives as an orphan (the
     * same failure class fixed for per-definition DSL hints in 80584c8).
     */
    private val _allModuleIds = linkedSetOf<String>()
    val allModuleIds: Set<String> get() = _allModuleIds

    private val _startKoinModules = mutableListOf<String>()
    val startKoinModules: List<String> get() = _startKoinModules

    /**
     * Set when any `modules(...)` / `includes(...)` argument could not be resolved to a `Module` val
     * — a list variable, a spread, a function call, a conditional.
     *
     * Reachability is only meaningful over a COMPLETE topology, so an unresolved argument makes
     * consumers fail OPEN (verify nothing) rather than trust a partial set. See [resolveModuleReferences].
     */
    private var _entryModulesIncomplete = false
    val entryModulesIncomplete: Boolean get() = _entryModulesIncomplete

    /** File:line of the `modules(...)` call that set [_entryModulesIncomplete], for KOIN-W003's "at:". */
    private var _entryModulesIncompleteOrigin: SourceOrigin? = null
    val entryModulesIncompleteOrigin: SourceOrigin? get() = _entryModulesIncompleteOrigin

    /**
     * Module vals whose own `includes(...)` had an argument we could not resolve, so their edge set
     * is PARTIAL.
     *
     * Scoped per module rather than per compilation: a single compilation-wide flag would let one
     * `includes(makeDebugModule())` anywhere switch off reachability for every entry point in it,
     * including unrelated production ones. Recorded per owner, an incomplete module only costs
     * verification when the walk actually reaches it.
     */
    private val _modulesWithIncompleteIncludes = linkedSetOf<String>()
    val modulesWithIncompleteIncludes: Set<String> get() = _modulesWithIncompleteIncludes

    private companion object {
        // SHARED — the module-typed receiver both codegen (which functions to intercept) and
        // safety (module-graph resolution) key off.
        const val KOIN_MODULE_FQNAME = "org.koin.core.module.Module"

        // SAFETY — module-graph resolution ([resolveModuleRef]) only. Stable List<Module>
        // constructors: same content every time, no branching — safe to recurse into their
        // arguments. Anything conditional (if/when) is a different, unstable case, not this.
        val TRANSPARENT_LIST_BUILDERS = setOf(
            "kotlin.collections.listOf", "kotlin.collections.listOfNotNull",
            "kotlin.collections.emptyList", "kotlin.arrayOf",
        )
        val TRANSPARENT_LIST_CONVERTERS = setOf("kotlin.collections.toList", "kotlin.collections.asList")

        // SAFETY — Koin's own constructor-shorthand DSL (org.koin.core.module.dsl), distinct from
        // this plugin's single<T>()/create(::T). Real Koin functions with ~20 reified-arity
        // overloads each, but resolving the `::Ctor`/`::function` argument itself needs none of
        // that: it's one IrFunctionReference regardless of arity, the same shape create(::T)
        // already resolves. So requirements ARE derived (see collectConstructorShorthandDef),
        // reusing requirementsFor — same helper, same correctness guarantee as create(::T), not a
        // constructor-vs-lambda guess. No codegen counterpart — the call is always left
        // untransformed (it already works at runtime).
        val CONSTRUCTOR_SHORTHAND_DEF_TYPES = mapOf(
            "org.koin.core.module.dsl.singleOf" to DefinitionType.SINGLE,
            "org.koin.core.module.dsl.factoryOf" to DefinitionType.FACTORY,
            "org.koin.core.module.dsl.scopedOf" to DefinitionType.SCOPED,
            "org.koin.core.module.dsl.viewModelOf" to DefinitionType.VIEW_MODEL,
        )
    }

    override fun visitFile(declaration: IrFile): IrFile {
        currentFile = declaration
        return super.visitFile(declaration)
    }

    // SHARED — used by codegen (to build the qualifier argument) and safety (to attach a
    // qualifier to a DslDef) alike.
    private val qualifierExtractor = QualifierExtractor(context)

    // SAFETY — attaches requirements to DslDefs at collection time (A3 durable model, PR1).
    // Shares qualifierExtractor so the metadata matches what BindingRegistry re-derives today.
    private val parameterAnalyzer = ParameterAnalyzer(qualifierExtractor)

    // CODEGEN — argument/lambda generation, reused from the annotation processor infrastructure.
    private val argumentGenerator = KoinArgumentGenerator(context, qualifierExtractor)
    private val lambdaBuilder = LambdaBuilder(context, qualifierExtractor, argumentGenerator)

    // SHARED — function names visitCall matches on, for both codegen dispatch and safety
    // collection (definitionNames/definitionTypeMap below key off these too).
    private val createName = Name.identifier("create")
    private val newName = Name.identifier("new")
    private val singleName = Name.identifier("single")
    private val factoryName = Name.identifier("factory")
    private val scopedName = Name.identifier("scoped")
    private val viewModelName = Name.identifier("viewModel")
    private val workerName = Name.identifier("worker")

    // CODEGEN — stub function name -> the real target (build*) function name.
    private val targetFunctionNames = mapOf(
        singleName to Name.identifier("buildSingle"),
        factoryName to Name.identifier("buildFactory"),
        scopedName to Name.identifier("buildScoped"),
        viewModelName to Name.identifier("buildViewModel"),
        workerName to Name.identifier("buildWorker")
    )

    // CODEGEN — cached class lookups (avoid repeated referenceClass calls).
    private val kClassClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KCLASS))?.owner }

    // CODEGEN — cache for target functions (buildSingle, buildFactory, etc.), see findTargetFunction.
    private val targetFunctionCache = mutableMapOf<Pair<Name, String>, IrSimpleFunction?>()

    /**
     * Context passed through the transformation to track the current position in the tree.
     * Using immutable data class with stack-based save/restore pattern for cleaner state management.
     *
     * @property function The enclosing function being visited
     * @property lambda The enclosing lambda (for create() validation)
     * @property definitionCall The enclosing DSL definition call (single/factory/scoped/etc.)
     * @property scopeTypeClass The scope type when inside a scope<ScopeType> { } block
     */
    private data class TransformContext(
        val function: IrFunction? = null,
        val lambda: IrSimpleFunction? = null,
        val definitionCall: Name? = null,
        // Type argument of the enclosing typed DSL call (e.g., `single<T> { }` → T).
        // When set, an inner `create(::Impl)` provides T, not Impl — Impl is the
        // construction detail, T is what runtime Koin actually registers.
        val definitionCallTypeArg: IrClass? = null,
        val definitionQualifier: QualifierValue? = null,
        val scopeTypeClass: IrClass? = null,
        val createQualifier: QualifierValue? = null,
        val createReturnClass: IrClass? = null,
        val modulePropertyId: String? = null
    )

    // Stack-based context management (thread-safe for single-threaded compiler)
    private var transformContext = TransformContext()

    // Convenience accessors for cleaner code
    private val currentFunction: IrFunction? get() = transformContext.function
    private val currentLambda: IrSimpleFunction? get() = transformContext.lambda
    private val currentDefinitionCall: Name? get() = transformContext.definitionCall

    override fun visitFunctionExpression(expression: IrFunctionExpression): IrExpression {
        return withContext(transformContext.copy(lambda = expression.function)) {
            super.visitFunctionExpression(expression)
        }
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        return withContext(contextForFunction(declaration)) {
            super.visitFunction(declaration)
        }
    }

    /**
     * SAFETY — if [declaration] is a `fun x(): Module` (e.g. `fun awsModule(): Module = module {}`),
     * registers it as a module source and pushes its module id so nested `single<T>()`/
     * `includes()` calls attribute to it. Otherwise just pushes the enclosing-function frame.
     */
    private fun contextForFunction(declaration: IrFunction): TransformContext {
        val baseContext = transformContext.copy(function = declaration)
        if (!compileSafetyEnabled || declaration !is IrSimpleFunction) return baseContext
        val functionModuleId = buildModuleFunctionId(declaration) ?: return baseContext
        KoinPluginLogger.debug { "Module-returning function: $functionModuleId" }
        _allModuleIds.add(functionModuleId)
        return baseContext.copy(modulePropertyId = functionModuleId)
    }

    override fun visitProperty(declaration: IrProperty): IrStatement {
        if (!compileSafetyEnabled) return super.visitProperty(declaration)
        val modulePropertyId = moduleIdForProperty(declaration) ?: return super.visitProperty(declaration)
        return withContext(transformContext.copy(modulePropertyId = modulePropertyId)) {
            super.visitProperty(declaration)
        }
    }

    /**
     * SAFETY — if [declaration] is a `val x: Module`, registers it as a module source and returns
     * its module id so nested `single<T>()`/`includes()` calls can attribute to it. Null (no
     * context pushed) if this property isn't Module-typed.
     */
    private fun moduleIdForProperty(declaration: IrProperty): String? {
        val isModuleType = declaration.backingField?.type?.classFqName?.asString() == "org.koin.core.module.Module"
        if (!isModuleType) return null
        val propertyId = buildModulePropertyId(declaration) ?: return null
        KoinPluginLogger.debug { "Module property: $propertyId" }
        _allModuleIds.add(propertyId)
        return propertyId
    }

    private fun buildModulePropertyId(property: IrProperty): String? {
        val parent = property.parent
        val packageName = when (parent) {
            is IrFile -> parent.packageFqName.asString()
            is IrClass -> parent.fqNameWhenAvailable?.asString()
            is IrPackageFragment -> parent.packageFqName.asString()
            else -> null
        } ?: return null
        return if (packageName.isEmpty()) property.name.asString()
        else "$packageName.${property.name.asString()}"
    }

    /**
     * Module id for a function returning `Module` (e.g. `fun awsModule(): Module = module {...}`) —
     * the function-based counterpart of [buildModulePropertyId]. Bare fqName: a `()` suffix would
     * flow unescaped into a synthetic hint function name, risky for KLIB/native serialization.
     *
     * Known limitation: a `val x: Module` and `fun x(): Module` sharing a name collide onto the same
     * id — not observed in real usage, not worth guarding against here.
     */
    private fun buildModuleFunctionId(function: IrSimpleFunction): String? {
        // Exclude property getters (e.g. `<get-appModule>`) — already identified via visitProperty.
        if (function.correspondingPropertySymbol != null) return null
        // Exclude the plugin's own generated `fun T.module()` extension (one per @Module class) —
        // would otherwise collide onto the same bare "module" id for every annotated class.
        if (function.extensionReceiverParam != null) return null
        if (function.returnType.classFqName?.asString() != KOIN_MODULE_FQNAME) return null
        val ownerOk = when (function.parent) {
            is IrFile, is IrClass, is IrPackageFragment -> true
            else -> false
        }
        if (!ownerOk) return null
        return function.fqNameWhenAvailable?.asString()
    }

    /** True for `List<Module>` — the collection counterpart of [KOIN_MODULE_FQNAME]. */
    private fun isModuleListType(type: IrType): Boolean {
        if (type.classFqName?.asString() != "kotlin.collections.List") return false
        val typeArg = (type as? IrSimpleType)?.arguments?.singleOrNull() as? IrType ?: return false
        return typeArg.classFqName?.asString() == KOIN_MODULE_FQNAME
    }

    /** Follows a function whose body is a single `return <expr>` — never guesses on anything more. */
    private fun resolveSimpleBodyReturn(function: IrSimpleFunction, result: MutableList<String>): Boolean {
        val body = function.body as? IrBlockBody ?: return false
        val statement = body.statements.singleOrNull() as? IrReturn ?: return false
        return resolveModuleRef(statement.value, result)
    }

    /**
     * Run [block] with a scoped [TransformContext], restoring the previous context afterward.
     * Qualifier propagation from inner create(::T) is preserved across the boundary so that
     * the enclosing definition call (single/factory/etc.) can pick it up.
     */
    private inline fun <T> withContext(newContext: TransformContext, block: () -> T): T {
        val previousContext = transformContext
        transformContext = newContext
        val result = block()
        val innerQualifier = transformContext.createQualifier
        val innerReturnClass = transformContext.createReturnClass
        transformContext = previousContext
        if (innerQualifier != null) {
            transformContext = transformContext.copy(
                createQualifier = innerQualifier,
                createReturnClass = innerReturnClass
            )
        }
        return result
    }

    // SHARED — DSL definition function names to track: codegen dispatches on these, safety
    // collection maps them to a DefinitionType (definitionTypeMap, right below).
    private val definitionNames = setOf(singleName, factoryName, scopedName, viewModelName, workerName)

    // SHARED — every function name visitCall may rewrite or safety-collect once descent is done.
    // Anything else is none of this plugin's business and bails out of visitCall early.
    private val dslTargetFunctionNames =
        definitionNames + setOf(createName, newName)

    // SAFETY — FqNames of Koin's bind DSL functions, consumed by collectBindType. Anything named
    // `bind` from outside this set is not ours (Arrow Raise.bind, ktor resourceScope bind, etc.)
    // and must be ignored.
    private val KOIN_BIND_FQNAMES = setOf(
        "org.koin.plugin.module.dsl.bind",
        "org.koin.dsl.bind",
        // Reified bind<Interface>() — no KClass value argument, used inside withOptions {}/singleOf(){}.
        "org.koin.core.module.dsl.bind",
    )

    // SAFETY — FqNames of Koin's multi-binding `binds(...)` DSL functions, consumed by
    // collectBindsTypes — the plural, vararg-array counterpart of `bind`, e.g.
    // `single<Impl>() binds arrayOf(IfaceA::class, IfaceB::class)`. Two real overloads:
    // `org.koin.dsl.binds(Array<KClass<*>>)` (infix, chained off single<T>()) and
    // `org.koin.core.module.dsl.binds(List<KClass<*>>)` (used inside withOptions {}).
    private val KOIN_BINDS_FQNAMES = setOf(
        "org.koin.dsl.binds",
        "org.koin.core.module.dsl.binds",
    )

    // SHARED — function name -> DefinitionType, consumed by both codegen (handleTypeParameterCall)
    // and safety (buildDslDef callers) to record what kind of definition this is.
    private val definitionTypeMap = mapOf(
        singleName to DefinitionType.SINGLE,
        factoryName to DefinitionType.FACTORY,
        scopedName to DefinitionType.SCOPED,
        viewModelName to DefinitionType.VIEW_MODEL,
        workerName to DefinitionType.WORKER
    )

    // SAFETY — FQ name strings of call-site resolution functions to intercept, consumed by
    // collectCallSiteIfResolutionFunction.
    private val callSiteResolutionFqNames: Set<String> =
        KoinAnnotationFqNames.CALL_SITE_RESOLUTION_FUNCTIONS.map { it.asString() }.toSet()

    // SHARED — scope function name for detecting scope<ScopeType> { } blocks (pushed into
    // transformContext.scopeTypeClass, read by both codegen and the SCOPED buildDslDef calls).
    private val scopeName = Name.identifier("scope")

    /**
     * Visit class declarations to collect call-site validations from member property delegates
     * (e.g., `by inject()`, `by viewModel()`). The default transformer doesn't traverse
     * backing field initializers of class properties.
     */
    override fun visitClass(declaration: IrClass): IrStatement {
        if (compileSafetyEnabled) {
            for (decl in declaration.declarations) {
                if (decl is IrProperty) {
                    val initializer = decl.backingField?.initializer
                    if (initializer != null) {
                        collectCallSitesFromExpression(initializer.expression)
                    }
                }
            }
        }
        return super.visitClass(declaration)
    }

    /**
     * Recursively scan an expression tree for call-site resolution functions.
     * Used for class property initializers which aren't visited by the standard transformer.
     */
    private fun collectCallSitesFromExpression(expression: IrExpression) {
        if (expression is IrCall) {
            val callee = expression.symbol.owner
            collectCallSiteIfResolutionFunction(expression, callee)
            // Recurse into call arguments
            for (i in 0 until expression.regularArgumentsCount) {
                val arg = expression.getRegularArgument(i)
                if (arg != null) collectCallSitesFromExpression(arg)
            }
            expression.extensionReceiverArgument?.let { collectCallSitesFromExpression(it) }
            expression.dispatchReceiver?.let { collectCallSitesFromExpression(it) }
        }
    }

    /**
     * visitCall runs both concerns over one node, in a fixed order: safety data that only needs
     * THIS call → context pushed for children → descend → safety data that needs the
     * FULLY-VISITED call → codegen dispatch. Each phase is named below so this reads as a table
     * of contents; see the individual `collect*`/`dispatchDslTargetCall` kdocs for the WHY.
     */
    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        val functionName = callee.name

        if (compileSafetyEnabled) collectPreDescentSafetyData(expression, callee)

        // ── SHARED CONTEXT: push state onto transformContext for CHILDREN of this call to read.
        // Both codegen (which target fn to build) and safety (which module/scope a nested def
        // belongs to) rely on this — it's not optional plumbing for either side. ──
        val previousContext = transformContext
        transformContext = childContextFor(expression, callee, functionName, transformContext)

        if (compileSafetyEnabled) collectConstructorShorthandDefIfApplicable(expression, callee)

        // SAFETY: snapshot the DSL-definition count so we can tell whether visiting the lambda body
        // already registered a definition (e.g. an inner create(::T)). If it did, the
        // provider-only fallback in dispatchDslTargetCall must NOT register a duplicate (a
        // duplicate DslDef → duplicate hint → KLIB SignatureClashDetector error on native/wasm).
        val dslDefsBeforeBody = _dslDefinitions.size

        // ── Descend into children (nested calls: create(::T), .bind<T>(), etc. get visited here) ──
        val transformedCall = super.visitCall(expression) as IrCall

        // SHARED CONTEXT: capture qualifier propagated from an inner create(::T) before restoring —
        // read by dispatchDslTargetCall (handleDefinitionWithCreateQualifier) to rewrite the
        // enclosing call.
        val propagatedQualifier = transformContext.createQualifier
        val propagatedReturnClass = transformContext.createReturnClass
        transformContext = previousContext

        if (compileSafetyEnabled) collectPostDescentSafetyData(functionName, callee, transformedCall)

        // ── CODEGEN: everything below rewrites (or dispatches a safety-only collector for) one of
        // this plugin's own target functions. Bail out now if this isn't one of ours. ──
        if (functionName !in dslTargetFunctionNames) return transformedCall

        return dispatchDslTargetCall(
            transformedCall, functionName, callee, propagatedQualifier, propagatedReturnClass, dslDefsBeforeBody
        )
    }

    /**
     * SAFETY — data that only needs THIS call, collected before descending into children:
     * koinViewModel<T>(), get<T>(), inject<T>() call sites, and includes()/modules() module-graph
     * edges.
     */
    private fun collectPreDescentSafetyData(expression: IrCall, callee: IrSimpleFunction) {
        collectCallSiteIfResolutionFunction(expression, callee)
        collectModuleLoadingInfo(expression, callee)
    }

    /**
     * SHARED CONTEXT — the TransformContext children of [expression] should see: the outer typed
     * DSL call's type argument/qualifier (so an inner `create(::Impl)` inside `single<T> { }`
     * registers T, not Impl, as the provided type — used by handleScopeCreate), or the
     * `scope<ScopeType> { }` type. Returns [current] unchanged when this call pushes nothing.
     */
    private fun childContextFor(
        expression: IrCall, callee: IrSimpleFunction, functionName: Name, current: TransformContext
    ): TransformContext {
        if (functionName in definitionNames) {
            val outerTypeArg = if (expression.typeArguments.size >= 1) {
                (expression.getTypeArgumentCompat(0)?.classifierOrNull as? IrClassSymbol)?.owner
            } else null
            val outerQualifier = extractQualifierArgument(expression, callee)
            return current.copy(
                definitionCall = functionName,
                definitionQualifier = outerQualifier,
                definitionCallTypeArg = outerTypeArg,
            )
        }
        if (functionName == scopeName && expression.typeArguments.size >= 1) {
            val scopeTypeArg = expression.getTypeArgumentCompat(0)
            val scopeTypeClass = (scopeTypeArg?.classifierOrNull as? IrClassSymbol)?.owner
            if (scopeTypeClass != null) {
                return current.copy(scopeTypeClass = scopeTypeClass)
            }
        }
        return current
    }

    /**
     * SAFETY — Koin's own constructor-shorthand DSL (singleOf(::Ctor)/factoryOf/scopedOf/
     * viewModelOf). Registered BEFORE super.visitCall so a trailing `{ bind<T>() }` options block
     * (visited as part of descending into this call's children) attaches to the right DslDef. No
     * codegen counterpart: the call is always left untransformed.
     */
    private fun collectConstructorShorthandDefIfApplicable(expression: IrCall, callee: IrSimpleFunction) {
        val defType = callee.fqNameWhenAvailable?.asString()?.let { CONSTRUCTOR_SHORTHAND_DEF_TYPES[it] } ?: return
        collectConstructorShorthandDef(expression, defType)
    }

    /**
     * SAFETY — collectors that need the FULLY-VISITED call: a chained `.bind<T>()`/`.binds(...)`/
     * `named(...)` options only exist on [transformedCall] once children have been visited.
     */
    private fun collectPostDescentSafetyData(functionName: Name, callee: IrSimpleFunction, transformedCall: IrCall) {
        // Detect Koin's .bind(Interface::class) — add the bound type to the last collected DslDef.
        // Match by full FqName so we don't trip on unrelated `bind` functions from other libraries
        // (e.g., Arrow `Raise.bind()`, ktor `resourceScope { bind() }`) — those crashed the IR
        // transformer by shape-mismatching KoinDefinition.bind's signature (issue #17).
        if (functionName.asString() == "bind" && callee.fqNameWhenAvailable?.asString() in KOIN_BIND_FQNAMES) {
            collectBindType(transformedCall)
        }

        // Detect Koin's multi-binding `binds(arrayOf(...))`/`binds(listOf(...))` — same idea as
        // `bind`, but a whole batch of bound types at once. Without this, a definition bound via
        // `binds(...)` silently carries NO bindings at all: KOIN-D001 then fires for every consumer
        // of every one of its bound interfaces, even though the definition genuinely provides them —
        // the worst failure class per this project's doctrine (silent > broken).
        if (functionName.asString() == "binds" && callee.fqNameWhenAvailable?.asString() in KOIN_BINDS_FQNAMES) {
            collectBindsTypes(transformedCall)
        }

        // Detect the options-block named(...)/named<T>() (org.koin.core.module.dsl) — sets the
        // qualifier on the last collected DslDef. Distinct from org.koin.core.qualifier.named(),
        // which QualifierExtractor already handles as a qualifier ARGUMENT value, not a statement.
        // NOT the same thing as constructor-requirement derivation (see
        // CONSTRUCTOR_SHORTHAND_DEF_TYPES's kdoc, handled separately in
        // collectConstructorShorthandDef) — this is the definition's OWN identity: what
        // qualifier it registers under. Skipping this collapses every qualified singleOf/factoryOf
        // registration of the same type into one unqualified provider entry, which is worse than
        // "unvalidated" — it's a false KOIN-D001 on correct code the moment something else requires
        // that qualified type (regression found in review, confirmed via
        // dsl_singleof_named_qualifier_disambiguates_consumer_ok).
        if (functionName.asString() == "named" && callee.fqNameWhenAvailable?.asString() == "org.koin.core.module.dsl.named") {
            collectNamedQualifier(transformedCall)
        }
    }

    /**
     * Receiver of a Koin DSL call site, resolved and validated once by [resolveKoinReceiver] and
     * shared by both [rewriteKoinDslCall] (CODEGEN) and [collectSafetyOnlyDslCall] (SAFETY) —
     * neither has to re-derive it.
     */
    private data class KoinReceiver(
        val receiver: IrExpression,
        val extensionReceiver: IrExpression?,
        val receiverClassifier: IrClass,
    )

    /**
     * [functionName] is already known to be one of this plugin's DSL target functions
     * (single/factory/scoped/viewModel/worker/create/new). Two clearly separated stages, in
     * order — first match wins:
     *
     *  1. [rewriteKoinDslCall] — CODEGEN. Is this one of the shapes this plugin generates code
     *     for? If so, the rewritten call is returned. A new codegen shape is added here.
     *  2. Otherwise the call is real, already-executable Koin code with nothing to rewrite —
     *     [collectSafetyOnlyDslCall] — SAFETY — just records what compile-safety needs to know
     *     about it. A new safety-only shape (a Koin DSL function this plugin will never rewrite)
     *     is added there.
     */
    private fun dispatchDslTargetCall(
        transformedCall: IrCall,
        functionName: Name,
        callee: IrSimpleFunction,
        propagatedQualifier: QualifierValue?,
        propagatedReturnClass: IrClass?,
        dslDefsBeforeBody: Int,
    ): IrExpression {
        val koinReceiver = resolveKoinReceiver(transformedCall) ?: return transformedCall

        rewriteKoinDslCall(transformedCall, functionName, koinReceiver, propagatedQualifier, propagatedReturnClass)
            ?.let { return it }

        collectSafetyOnlyDslCall(transformedCall, functionName, callee, koinReceiver, dslDefsBeforeBody)
        return transformedCall
    }

    /**
     * Resolves the receiver of a DSL call site (extension or dispatch — dispatch covers implicit
     * `this` inside a lambda) and confirms it's actually Koin's own `org.koin.core`/`org.koin.dsl`
     * API. Null for anything else — [dispatchDslTargetCall] bails out untransformed.
     */
    private fun resolveKoinReceiver(transformedCall: IrCall): KoinReceiver? {
        val extensionReceiver = transformedCall.extensionReceiverArgument
        val dispatchReceiver = transformedCall.dispatchReceiver
        val receiver = extensionReceiver ?: dispatchReceiver ?: return null

        val receiverClassifier = receiver.type.classifierOrNull?.owner as? IrClass ?: return null
        val receiverPackage = receiverClassifier.packageFqName?.asString()
        if (receiverPackage == null || (!receiverPackage.startsWith("org.koin.core") && !receiverPackage.startsWith("org.koin.dsl"))) {
            return null
        }
        return KoinReceiver(receiver, extensionReceiver, receiverClassifier)
    }

    /**
     * CODEGEN (+ inline SAFETY DslDef collection where a handler has already resolved what safety
     * needs). Every shape here rewrites [transformedCall] into what this plugin actually
     * generates. Returns null when [transformedCall] isn't one of these shapes — NOT the same as
     * "leave untransformed": that's [collectSafetyOnlyDslCall]'s job.
     */
    private fun rewriteKoinDslCall(
        transformedCall: IrCall,
        functionName: Name,
        koinReceiver: KoinReceiver,
        propagatedQualifier: QualifierValue?,
        propagatedReturnClass: IrClass?,
    ): IrExpression? {
        val receiver = koinReceiver.receiver
        val receiverClassifier = koinReceiver.receiverClassifier

        // Propagate qualifier from create(::ref) to enclosing definition call.
        // When single { create(::qualifiedFunc) } is used, the qualifier from the function
        // must be applied to the single definition registration
        if (functionName in definitionNames && propagatedQualifier != null) {
            val returnClass = propagatedReturnClass!!
            return handleDefinitionWithCreateQualifier(
                transformedCall, receiver, receiverClassifier, functionName, returnClass, propagatedQualifier
            )
        }

        // single<T>(), factory<T>(), etc.
        if (transformedCall.regularArgumentsCount == 0 && transformedCall.typeArguments.size >= 1 &&
            koinReceiver.extensionReceiver != null
        ) {
            return handleTypeParameterCall(transformedCall, koinReceiver.extensionReceiver, receiverClassifier, functionName)
        }

        // create(::Constructor) or create(::function) for Scope.create. Works with both extension
        // receiver (scope.create) and dispatch receiver (this.create in lambda).
        if (functionName == createName && receiverClassifier.name.asString() == "Scope") {
            val functionRef = transformedCall.getRegularArgument(0) as? IrFunctionReference ?: return transformedCall
            return handleScopeCreate(transformedCall, functionRef.symbol.owner, receiver)
        }

        return null
    }

    /**
     * SAFETY ONLY — [transformedCall] is real, already-executable Koin code that
     * [rewriteKoinDslCall] left untouched; this just records what compile-safety needs to know
     * about it. A new safety-only DSL shape (a Koin function this plugin will never rewrite) is
     * added as its own guarded block here, following the same shape as the two below.
     */
    private fun collectSafetyOnlyDslCall(
        transformedCall: IrCall,
        functionName: Name,
        callee: IrSimpleFunction,
        koinReceiver: KoinReceiver,
        dslDefsBeforeBody: Int,
    ) {
        // Koin's OWN new(::Constructor) / new(::function) — org.koin.core.module.dsl.new. Same
        // constructor-reference shape as create(::T), but a REAL Koin runtime function this
        // plugin does not need to rewrite (its body already resolves args via get()). Only the
        // requirement-registration half applies — see collectScopeNewDef's kdoc for why skipping
        // this was a silent false-negative, not a false-positive. Deliberately NOT gated by
        // compileSafetyEnabled (unlike the block below) — collectScopeNewDef's own kdoc covers why.
        if (functionName == newName && koinReceiver.receiverClassifier.name.asString() == "Scope" &&
            callee.fqNameWhenAvailable?.asString() == "org.koin.core.module.dsl.new"
        ) {
            val functionRef = transformedCall.getRegularArgument(0) as? IrFunctionReference ?: return
            collectScopeNewDef(functionRef.symbol.owner)
            return
        }

        // Typed DSL definition with a user-provided lambda body that is NOT create(::T):
        // single<T> { existingInstance }, single<T> { provideX() }, viewModel { VM() }, ... The
        // declared/inferred type argument T is what the definition provides, regardless of the
        // lambda body — register it as an available (provider-only) definition so compile-safety
        // doesn't raise a false missing-definition (issues #36, #49). providerOnly = true keeps
        // requirements empty (see ParameterAnalyzer.requirementsForDslDefinition) — the body stays
        // opaque, so its own dependency graph edges (and therefore KOIN-D004 cycle detection
        // through it) are never derived. Its literal get()/inject()/getOrNull() calls are still
        // caught independently as ordinary call sites (Phase 3.5/A4, GeneratedResolutionCallRegistry
        // skips only calls the PLUGIN generated, not hand-written ones) — see
        // docs/COMPILE_TIME_SAFETY.md for what is and isn't covered for this DSL shape.
        // Skipped when visiting the lambda already registered a definition (inner create(::T)),
        // so we never emit a duplicate DslDef.
        if (functionName in definitionNames && compileSafetyEnabled &&
            _dslDefinitions.size == dslDefsBeforeBody
        ) {
            val defType = definitionTypeMap[functionName]
            val providedClass = transformedCall.getTypeArgumentCompat(0)?.classifierOrNull?.owner as? IrClass
            if (defType != null && providedClass != null) {
                val qualifier = extractQualifierArgument(transformedCall, callee) ?: qualifierExtractor.extractFromClass(providedClass)
                trackClassLookup(lookupTracker, currentFile, providedClass)
                linkDeclarationsForIC(expectActualTracker, currentFile, providedClass)
                _dslDefinitions.add(buildDslDef(providedClass, defType, qualifier, providerOnly = true) {
                    parameterAnalyzer.requirementsForDslDefinition(providedClass, providerOnly = true)
                })
                KoinPluginLogger.user { "Intercepting $functionName<${providedClass.name}> { ... } (provider-only)" }
            }
        }
    }

    /**
     * SAFETY ONLY. If the call is a Koin resolution function (koinViewModel<T>(), get<T>(),
     * inject<T>(), etc.), collect it as a pending call-site validation. Also captures any trailing
     * `parametersOf(...)` lambda for KOIN-D005/D006 shape checks downstream.
     *
     * Skips a call [GeneratedResolutionCallRegistry] knows the plugin itself generated (e.g. a
     * `get()` KoinArgumentGenerator inserted into an annotation-derived
     * `single<Service> { Service(get()) }` body during Phase 1, walked completely normally once
     * Phase 2 reaches it). That get() is the mechanical realization of a requirement
     * BindingRegistry already validated structurally — collecting it too would double-report the
     * same missing dependency via two independent mechanisms. Checked first, before the FqName
     * lookup below (cheaper, and correct regardless of which tracked function the plugin happens
     * to generate calls to).
     */
    private fun collectCallSiteIfResolutionFunction(expression: IrCall, callee: IrSimpleFunction) {
        if (GeneratedResolutionCallRegistry.isGenerated(expression)) return
        val calleeFqName = callee.fqNameWhenAvailable?.asString() ?: return
        if (calleeFqName !in callSiteResolutionFqNames) return
        if (callee.typeParameters.isEmpty()) return

        val typeArg = expression.getTypeArgumentCompat(0) ?: return
        val targetClass = (typeArg.classifierOrNull as? IrClassSymbol)?.owner ?: return
        val targetFqName = targetClass.fqNameWhenAvailable?.asString() ?: return

        val file = currentFile
        val filePath = file?.fileEntry?.name
        val line = if (file != null && expression.startOffset >= 0) {
            file.fileEntry.getLineNumber(expression.startOffset) + 1
        } else 0
        val column = if (file != null && expression.startOffset >= 0) {
            file.fileEntry.getColumnNumber(expression.startOffset) + 1
        } else 0

        // Walk the whole arg tree rather than locating "the lambda" first — Compose's IR plugin
        // buries a Composable's trailing lambda inside scaffolding (see findParametersOfCall).
        var parametersOfCall: IrCall? = null
        for (i in 0 until expression.regularArgumentsCount) {
            val arg = expression.getRegularArgument(i) ?: continue
            parametersOfCall = findParametersOfCall(arg) ?: continue
            break
        }
        val parametersOfArgs = parametersOfCall?.let { extractParametersOfArgs(it) }

        // A params lambda was *passed* iff the resolution function's `parameters` slot has a
        // non-null argument — independently of whether a *direct* parametersOf(...) is
        // statically visible inside it. An indirect helper (`{ buildParams() }`) is a
        // present-but-opaque lambda: KOIN-D006 ("forgot parametersOf entirely") must NOT fire
        // for it (issue #61). It is treated as ambiguous downstream (parametersOfArgs == null →
        // shape check skipped); only a call site with NO params lambda at all fires KOIN-D006.
        val parametersParamIndex = callee.regularParameters.indexOfFirst { it.name.asString() == "parameters" }
        val paramsLambdaPresent = parametersParamIndex >= 0 &&
            expression.getRegularArgument(parametersParamIndex) != null

        _pendingCallSites.add(PendingCallSiteValidation(
            targetFqName = targetFqName,
            targetClass = targetClass,
            callFunctionName = calleeFqName.substringAfterLast("."),
            filePath = filePath,
            line = line,
            column = column,
            hasParametersLambda = paramsLambdaPresent,
            parametersOfArgs = parametersOfArgs,
        ))

        // IC: call site file depends on the target class
        trackClassLookup(lookupTracker, currentFile, targetClass)
        linkDeclarationsForIC(expectActualTracker, currentFile, targetClass)
    }

    /**
     * Walk an arbitrary expression tree looking for the first `parametersOf(...)` call.
     *
     * Compose's IR plugin rewrites trailing lambdas passed to @Composable functions into
     * `IrBlock { sourceInformationMarkerStart(...); val tmp = remember(..., { user-lambda });
     * sourceInformationMarkerEnd(...); tmp }` — i.e., the user's lambda is buried inside an
     * IrVariable initializer that's an IrCall whose arguments include another IrFunctionExpression
     * wrapping the original body. Recursing for `parametersOf` cuts through that scaffolding
     * regardless of the exact shape, and falls back cleanly for the non-Compose case (where the
     * top-level arg is already an IrFunctionExpression).
     */
    private fun findParametersOfCall(node: IrElement?): IrCall? =
        findParametersOfCall(node, java.util.IdentityHashMap<IrElement, Unit>())

    private fun findParametersOfCall(
        node: IrElement?,
        visited: java.util.IdentityHashMap<IrElement, Unit>,
    ): IrCall? {
        if (node == null) return null
        // Function references and IrFunctionExpression resolve to function bodies we may also
        // reach by descending into call arguments — guard with identity to avoid revisits and
        // (in pathological IR) infinite recursion on cyclic references.
        if (visited.put(node, Unit) != null) return null
        if (node is IrCall) {
            val fqName = node.symbol.owner.fqNameWhenAvailable?.asString()
            if (fqName == KoinAnnotationFqNames.PARAMETERS_OF.asString()) return node
            for (i in 0 until node.regularArgumentsCount) {
                findParametersOfCall(node.getRegularArgument(i), visited)?.let { return it }
            }
            findParametersOfCall(node.dispatchReceiver, visited)?.let { return it }
            findParametersOfCall(node.extensionReceiverArgument, visited)?.let { return it }
        }
        if (node is IrFunctionExpression) {
            return findParametersOfCall(node.function.body, visited)
        }
        if (node is IrFunctionReference) {
            return findParametersOfCall((node.symbol.owner as? IrSimpleFunction)?.body, visited)
        }
        if (node is IrBlockBody) {
            for (stmt in node.statements) findParametersOfCall(stmt, visited)?.let { return it }
        }
        if (node is IrContainerExpression) {
            for (stmt in node.statements) findParametersOfCall(stmt, visited)?.let { return it }
        }
        if (node is IrReturn) return findParametersOfCall(node.value, visited)
        if (node is IrVariable) return findParametersOfCall(node.initializer, visited)
        if (node is IrTypeOperatorCall) return findParametersOfCall(node.argument, visited)
        if (node is IrSetValue) return findParametersOfCall(node.value, visited)
        return null
    }

    /**
     * Extract `parametersOf(arg0, arg1, …)` args from the body of a trailing lambda. Returns:
     *  - non-null list when the body is a single (possibly returned) `parametersOf(...)` call.
     *    Each entry carries the arg's classifier FqName + nullability for shape comparison.
     *  - `null` when the lambda body is anything else (non-trivial: `{ buildHolder() }`,
     *    conditional, multi-statement) — treated as ambiguous downstream so we don't false-
     *    positive KOIN-D005 on hand-written param builders.
     *
     * Strict by design — matches the `unsafeDslChecks` posture used elsewhere in the plugin.
     */
    private fun extractParametersOfArgs(
        call: IrCall,
    ): List<BindingRegistry.Companion.ParametersOfArg>? {
        // parametersOf is `vararg values: Any?` — a single value-argument that's an IrVararg.
        val args = mutableListOf<BindingRegistry.Companion.ParametersOfArg>()
        if (call.regularArgumentsCount == 0) return args
        val varargArg = call.getRegularArgument(0)
        when (varargArg) {
            is IrVararg -> {
                for (element in varargArg.elements) {
                    args.add(classifyParametersOfArg(element))
                }
            }
            null -> { /* parametersOf() with no args */ }
            else -> {
                // Single non-vararg arg (e.g., spread-only). Best-effort classify as-is.
                args.add(classifyParametersOfArg(varargArg))
            }
        }
        return args
    }

    /**
     * Classify one positional `parametersOf` argument into a [BindingRegistry.Companion.ParametersOfArg]
     * for shape comparison. Spread operators (`*list`) and unclassifiable expressions yield an
     * ambiguous marker (`typeFqName=null, isNullable=false`) so the downstream validator skips
     * the whole call rather than emit a false mismatch.
     */
    private fun classifyParametersOfArg(
        element: org.jetbrains.kotlin.ir.IrElement,
    ): BindingRegistry.Companion.ParametersOfArg {
        // Spread element — can't classify its contents statically.
        if (element is IrSpreadElement) {
            return BindingRegistry.Companion.ParametersOfArg(typeFqName = null, isNullable = false)
        }

        val expr = element as? IrExpression
            ?: return BindingRegistry.Companion.ParametersOfArg(typeFqName = null, isNullable = false)

        // null literal
        if (expr is IrConst && expr.value == null) {
            return BindingRegistry.Companion.ParametersOfArg(typeFqName = null, isNullable = true)
        }

        val type = expr.type
        val classifier = type.classifierOrNull?.owner as? IrClass
        val fqName = classifier?.fqNameWhenAvailable?.asString()
        return BindingRegistry.Companion.ParametersOfArg(
            typeFqName = fqName,
            isNullable = type.isMarkedNullable(),
        )
    }

    /**
     * SAFETY ONLY. Detect .bind(Interface::class) and add the bound type to the last collected
     * DslDef. The inner single<T>()/factory<T>() has already been processed and its DslDef added.
     */
    private fun collectBindType(expression: IrCall) {
        if (_dslDefinitions.isEmpty()) return
        val lastDef = _dslDefinitions.last()

        // Extract the KClass argument from bind(clazz: KClass<S>), or fall back to the reified type
        // argument of bind<Interface>() — a distinct Koin DSL shape with no value argument at all.
        val classArg = expression.getRegularArgument(0)
        val boundClass = if (classArg != null) {
            when (classArg) {
                is IrClassReference -> {
                    val classifier = classArg.classType.classifierOrNull
                    when (classifier) {
                        is IrClassSymbol -> classifier.owner
                        is IrClass -> classifier
                        else -> null
                    }
                }
                else -> null
            }
        } else {
            (expression.getTypeArgumentCompat(0)?.classifierOrNull as? IrClassSymbol)?.owner
        }
        if (boundClass == null) {
            // Confirmed to be a real Koin bind() call at the caller (fqName already matched
            // KOIN_BIND_FQNAMES) — any resolution failure here is a genuinely unanalyzable shape
            // (e.g. bind(someVariable)), not a false positive. Disclose (KOIN-W006) instead of
            // silently dropping — see UnresolvedBindArgument's kdoc.
            KoinPluginLogger.report(KoinDiagnostic.UnresolvedBindArgument(
                defName = lastDef.returnTypeClass.name.asString(), functionName = "bind"
            ))
            return
        }

        // Update the last DslDef with the additional binding
        if (boundClass.fqNameWhenAvailable?.asString() !in lastDef.bindings.mapNotNull { it.fqNameWhenAvailable?.asString() }) {
            // retainA3Metadata is REQUIRED here: copy() re-runs the primary constructor, so the
            // body-held requirements/origin would reset and this definition's constructor
            // dependencies would go unvalidated (see Definition.retainA3Metadata).
            _dslDefinitions[_dslDefinitions.lastIndex] = lastDef.copy(
                bindings = lastDef.bindings + boundClass
            ).retainA3Metadata(lastDef)
            KoinPluginLogger.debug { "  bind: ${lastDef.returnTypeClass.name} -> ${boundClass.name}" }
        }
    }

    /** One `X::class` element inside a `binds(arrayOf(...))`/`binds(listOf(...))` argument. */
    private fun classRefToIrClass(ref: IrClassReference): IrClass? =
        when (val classifier = ref.classType.classifierOrNull) {
            is IrClassSymbol -> classifier.owner
            is IrClass -> classifier
            else -> null
        }

    /**
     * Extract the `X::class, Y::class, ...` elements of a `binds(arrayOf(...))`/`binds(listOf(...))`
     * call's single argument. `arrayOf`/`listOf` are themselves vararg functions, so their own
     * argument is an [IrVararg] whose elements are the class-literal expressions — this only needs to
     * unwrap one level, unlike [org.koin.compiler.plugin.ir.KoinDSLTransformer.resolveModuleRef]'s
     * broader walk (no `+`/`.toList()`/spread support here — `binds` never sees those in practice).
     */
    private fun extractClassReferenceList(expression: IrExpression?): List<IrClass> {
        val vararg = when (expression) {
            is IrVararg -> expression
            is IrCall -> expression.getRegularArgument(0) as? IrVararg
            else -> null
        } ?: return emptyList()
        return vararg.elements.mapNotNull { (it as? IrClassReference)?.let(::classRefToIrClass) }
    }

    /** SAFETY ONLY. Detect Koin's `binds(arrayOf(...))`/`binds(listOf(...))` — adds every bound
     *  type to the last collected DslDef, same as [collectBindType] but for a whole batch at once. */
    private fun collectBindsTypes(expression: IrCall) {
        if (_dslDefinitions.isEmpty()) return
        val boundClasses = extractClassReferenceList(expression.getRegularArgument(0))
        if (boundClasses.isEmpty()) return

        val lastDef = _dslDefinitions.last()
        val existingFqNames = lastDef.bindings.mapNotNullTo(mutableSetOf()) { it.fqNameWhenAvailable?.asString() }
        val newBindings = boundClasses.filter { it.fqNameWhenAvailable?.asString() !in existingFqNames }
        if (newBindings.isNotEmpty()) {
            // retainA3Metadata — see collectBindType's identical note.
            _dslDefinitions[_dslDefinitions.lastIndex] = lastDef.copy(
                bindings = lastDef.bindings + newBindings
            ).retainA3Metadata(lastDef)
            KoinPluginLogger.debug { "  binds: ${lastDef.returnTypeClass.name} -> ${newBindings.map { it.name }}" }
        }
    }

    /**
     * SAFETY ONLY. Options-block `named("x")`/`named<T>()` — sets the qualifier on the last
     * collected DslDef. This is identity, not requirement-derivation: it records what qualifier
     * the definition ITSELF provides under, so a distinct definition of the same type disambiguated
     * by qualifier is correctly recognized as a distinct provider (see this call site's own comment
     * in visitCall for why deleting this was a real regression, not a safe precision downgrade).
     */
    private fun collectNamedQualifier(expression: IrCall) {
        if (_dslDefinitions.isEmpty()) return

        val nameArg = expression.getRegularArgument(0)
        val qualifier = if (nameArg != null) {
            ((nameArg as? IrConst)?.value as? String)?.let { QualifierValue.StringQualifier(it) }
        } else {
            (expression.getTypeArgumentCompat(0)?.classifierOrNull as? IrClassSymbol)?.owner
                ?.let { QualifierValue.TypeQualifier(it) }
        } ?: return

        val lastDef = _dslDefinitions.last()
        _dslDefinitions[_dslDefinitions.lastIndex] = lastDef.copy(qualifier = qualifier).retainA3Metadata(lastDef)
        KoinPluginLogger.debug { "  named: ${lastDef.returnTypeClass.name} -> $qualifier" }
    }

    /** SAFETY ONLY. Records `includes()`/`modules()` edges into the module graph used to compute
     *  reachability (see [CallSiteValidator.validateDslDefinitionGraph]'s reachable-modules walk). */
    private fun collectModuleLoadingInfo(expression: IrCall, callee: IrSimpleFunction) {
        val functionName = callee.name.asString()
        if (functionName == "includes") {
            val receiverType = (callee.extensionReceiverParam ?: callee.dispatchReceiverParameter)
                ?.type?.classFqName?.asString()
            if (receiverType == "org.koin.core.module.Module") {
                val currentModuleId = transformContext.modulePropertyId ?: return
                val (includedModules, complete) = resolveModuleReferences(expression)
                if (!complete) {
                    _modulesWithIncompleteIncludes.add(currentModuleId)
                    KoinPluginLogger.debug { "  includes: $currentModuleId has an UNRESOLVABLE argument — its edge set is partial" }
                }
                if (includedModules.isNotEmpty()) {
                    _moduleIncludes.getOrPut(currentModuleId) { mutableListOf() }.addAll(includedModules)
                    KoinPluginLogger.debug { "  includes: $currentModuleId -> $includedModules" }
                }
            }
            return
        }
        if (functionName == "modules") {
            val receiverType = (callee.extensionReceiverParam ?: callee.dispatchReceiverParameter)
                ?.type?.classFqName?.asString()
            if (receiverType == "org.koin.core.KoinApplication") {
                val (loadedModules, complete) = resolveModuleReferences(expression)
                if (!complete) {
                    _entryModulesIncomplete = true
                    if (_entryModulesIncompleteOrigin == null) {
                        val file = currentFile
                        val line = if (file != null && expression.startOffset >= 0) {
                            file.fileEntry.getLineNumber(expression.startOffset) + 1
                        } else null
                        _entryModulesIncompleteOrigin = SourceOrigin(moduleFqName = null, filePath = file?.fileEntry?.name, line = line)
                    }
                    KoinPluginLogger.debug { "  modules() at startKoin has an UNRESOLVABLE argument — loaded set unknown" }
                }
                if (loadedModules.isNotEmpty()) {
                    _startKoinModules.addAll(loadedModules)
                    KoinPluginLogger.debug { "  modules() at startKoin: $loadedModules" }
                }
            }
        }
    }

    /**
     * Resolve the module vals referenced by a `modules(...)` / `includes(...)` call.
     *
     * Any argument we cannot resolve to a `Module`-typed property marks [ResolvedModules.complete]
     * false — treating a partial result as the whole loaded set turns correct code into errors.
     * Regression: `modules(listOf(appModule, coreModule))` used to record the LIST property as the
     * loaded module, leaving the real modules "unreachable"; once call-site validation began
     * withholding unreachable-only types that became a KOIN-D002 on a valid graph. See
     * `entry_modules_list_variable_ok.kt`.
     */
    private fun resolveModuleReferences(call: IrCall): ResolvedModules {
        val result = mutableListOf<String>()
        var complete = true
        for (i in 0 until call.regularArgumentsCount) {
            val arg = call.getRegularArgument(i)
            if (arg == null || !resolveModuleRef(arg, result)) {
                complete = false
                KoinPluginLogger.debug {
                    "  modules()/includes(): argument #$i is not a resolvable Module val"
                }
            }
        }
        return ResolvedModules(result, complete)
    }

    /** Module ids read off a `modules(...)`/`includes(...)` call, and whether ALL arguments resolved. */
    data class ResolvedModules(val ids: List<String>, val complete: Boolean)

    /** @return true when this expression resolved to a `Module`-typed property (or all of them, for a vararg). */
    private fun resolveModuleRef(expression: IrExpression, result: MutableList<String>): Boolean {
        // The type guard is the point: without it ANY argument was recorded as a module, including a
        // `List<Module>` passed to Koin's `modules(modules: List<Module>)` overload.
        fun record(property: IrProperty?, isModuleTyped: Boolean): Boolean {
            if (!isModuleTyped || property == null) return false
            val propId = buildModulePropertyId(property) ?: return false
            result.add(propId)
            return true
        }
        return when (expression) {
            is IrGetField -> {
                val field = expression.symbol.owner
                when {
                    field.type.classFqName?.asString() == KOIN_MODULE_FQNAME ->
                        record(field.correspondingPropertySymbol?.owner, true)
                    // A List<Module>-typed property is just a container, not a module of its own —
                    // look through to its initializer rather than minting an id for it.
                    isModuleListType(field.type) ->
                        field.initializer?.expression?.let { resolveModuleRef(it, result) } ?: false
                    else -> false
                }
            }
            is IrCall -> {
                val callee = expression.symbol.owner
                val calleeFqName = callee.fqNameWhenAvailable?.asString()
                val isModuleTyped = expression.type.classFqName?.asString() == KOIN_MODULE_FQNAME
                val property = callee.correspondingPropertySymbol?.owner
                when {
                    property != null && isModuleTyped -> record(property, true)
                    // A List<Module>-typed property accessed via its getter call (not IrGetField, in
                    // this context) — same "look through to the initializer" as the IrGetField case.
                    property != null && isModuleListType(expression.type) ->
                        property.backingField?.initializer?.expression?.let { resolveModuleRef(it, result) } ?: false
                    // Virtual dispatch excluded: an override's fqName doesn't identify which body runs.
                    isModuleTyped && expression.dispatchReceiver == null && callee is IrSimpleFunction -> {
                        val fnId = buildModuleFunctionId(callee)
                        if (fnId != null) {
                            result.add(fnId)
                            true
                        } else false
                    }
                    // listOf/listOfNotNull/emptyList/arrayOf — same content every call, recurse into args.
                    calleeFqName in TRANSPARENT_LIST_BUILDERS && expression.dispatchReceiver == null ->
                        (0 until expression.regularArgumentsCount).all { i ->
                            expression.getRegularArgument(i)?.let { resolveModuleRef(it, result) } ?: true
                        }
                    // a + b — both operands must resolve.
                    calleeFqName == "kotlin.collections.plus" -> {
                        val receiver = expression.extensionReceiverArgument
                        val arg = expression.getRegularArgument(0)
                        receiver != null && arg != null &&
                            resolveModuleRef(receiver, result) && resolveModuleRef(arg, result)
                    }
                    // .toList()/.asList() — only as good as whatever they're converting.
                    calleeFqName in TRANSPARENT_LIST_CONVERTERS ->
                        expression.extensionReceiverArgument?.let { resolveModuleRef(it, result) } ?: false
                    // A function returning List<Module> via a single-statement body — follow it, same
                    // guard as the Module-returning case (no virtual dispatch).
                    expression.dispatchReceiver == null && callee is IrSimpleFunction &&
                        isModuleListType(expression.type) -> resolveSimpleBodyReturn(callee, result)
                    else -> false
                }
            }
            // A vararg counts as resolved only if EVERY element did; a partially-read spread is
            // exactly the "we don't know the whole set" case.
            is IrVararg -> expression.elements.all { it is IrExpression && resolveModuleRef(it, result) }
            // A local `val` (not `var`) — trace to its initializer. Parameters (incl. vararg params)
            // are a different IrValueParameter case, deliberately excluded: not this compilation's to know.
            is IrGetValue -> {
                val variable = expression.symbol.owner as? IrVariable
                if (variable == null || variable.isVar) false
                else variable.initializer?.let { resolveModuleRef(it, result) } ?: false
            }
            else -> false
        }
    }

    /**
     * CODEGEN, with one inline SAFETY step: rewrites single<T>(), factory<T>(), scoped<T>(),
     * viewModel<T>(), worker<T>() into the real `build*(KClass, qualifier) { T(get(), get()...) }`
     * call. The SAFETY DslDef collection below is inline (rather than a separate `collect*` call)
     * because it needs exactly the [targetClass]/[qualifier] codegen already resolved — computing
     * them twice would defeat the point of a single tree walk.
     */
    private fun handleTypeParameterCall(
        call: IrCall,
        extensionReceiver: IrExpression,
        receiverClassifier: IrClass,
        functionName: Name
    ): IrExpression {
        val typeArg = call.getTypeArgumentCompat(0) ?: return call
        val targetClass = typeArg.classifierOrNull?.owner as? IrClass ?: return call
        val constructor = targetClass.primaryConstructor
        if (constructor == null) {
            KoinPluginLogger.debug { "$functionName<${targetClass.name}>() skipped - no primary constructor" }
            return call
        }

        // IC: file containing DSL call depends on the target class
        trackClassLookup(lookupTracker, currentFile, targetClass)
        linkDeclarationsForIC(expectActualTracker, currentFile, targetClass)

        // Get qualifier from @Named or @Qualifier annotation on class
        val qualifier = qualifierExtractor.extractFromClass(targetClass)

        // SAFETY: collect the DslDef now, reusing targetClass/qualifier from codegen above —
        // this is the one place safety data collection happens inline rather than via a
        // dedicated collect* function (see this function's kdoc for why).
        val defType = definitionTypeMap[functionName]
        if (defType != null && compileSafetyEnabled) {
            _dslDefinitions.add(buildDslDef(targetClass, defType, qualifier) { parameterAnalyzer.requirementsForClass(targetClass) })
        }

        // ── back to CODEGEN ──
        val receiverClassName = receiverClassifier.name.asString()

        // Log the interception
        KoinPluginLogger.user { "Intercepting $functionName<${targetClass.name}>() on $receiverClassName" }

        val builder = DeclarationIrBuilder(context, call.symbol, call.startOffset, call.endOffset)

        // Find target function with KClass parameter
        val targetFunction = findTargetFunction(functionName, receiverClassName)
        if (targetFunction == null) {
            KoinPluginLogger.debug { "$functionName target function not found for $receiverClassName" }
            return call
        }

        // For worker definitions, use class name as qualifier (required by WorkManager)
        val effectiveQualifier: QualifierValue? = if (functionName == workerName) {
            QualifierValue.StringQualifier(targetClass.fqNameWhenAvailable?.asString() ?: targetClass.name.asString())
        } else {
            qualifier
        }

        // Build the transformed call
        return builder.irCall(targetFunction.symbol).apply {
            setExtensionReceiverArgument(extensionReceiver)
            putTypeArgumentCompat(0, targetClass.defaultType)

            // Arg 0: KClass<T>
            val kClassClassOwner = kClassClass ?: return call
            putRegularArgument(0, IrClassReferenceImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                kClassClassOwner.typeWith(targetClass.defaultType),
                targetClass.symbol,
                targetClass.defaultType
            ))

            // Arg 1: Qualifier? (for workers, always use class name as qualifier)
            putRegularArgument(1, qualifierExtractor.createQualifierCall(effectiveQualifier, builder) ?: builder.irNull())

            // Arg 2: Definition lambda { T(get(), get(), ...) }
            val parentFunc = currentFunction ?: return call
            putRegularArgument(2, lambdaBuilder.create(targetClass, builder, parentFunc) { lb, scopeParam, paramsParam ->
                lb.irCallConstructor(constructor.symbol, emptyList()).apply {
                    constructor.regularParameters.forEachIndexed { index, param ->
                        val scopeGet = lb.irGet(scopeParam)
                        val paramsGet = lb.irGet(paramsParam)
                        val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeGet, paramsGet, lb)
                        if (argument != null) {
                            putRegularArgument(index, argument)
                        }
                    }
                }
            })
        }
    }

    /**
     * Register a DslDef for Koin's own singleOf(::Ctor)/factoryOf/scopedOf/viewModelOf — the
     * constructor/function reference's target class is the provided type, AND its requirements
     * ARE derived from the referenced declaration's own parameters via [requirementsFor] — the
     * same shared helper `create(::T)` uses, not a guess from the provided class's own
     * constructor (that specific mistake was the real bug once found here, see 4db7c11; the fix
     * was to always resolve from `referencedFunction`, which this already does). Unlike an opaque
     * hand-written lambda body, there's no free-form code here to be opaque about — `::Ctor` is
     * one resolvable declaration regardless of which of Koin's ~20 reified-arity overloads called
     * it. The call itself is left untransformed (it already works at runtime).
     */
    private fun collectConstructorShorthandDef(expression: IrCall, defType: DefinitionType) {
        val functionRef = expression.getRegularArgument(0) as? IrFunctionReference ?: return
        val referencedFunction = functionRef.symbol.owner
        val targetClass = when (referencedFunction) {
            is IrConstructor -> referencedFunction.parent as? IrClass
            is IrSimpleFunction -> referencedFunction.returnType.classifierOrNull?.owner as? IrClass
        } ?: return
        trackClassLookup(lookupTracker, currentFile, targetClass)
        linkDeclarationsForIC(expectActualTracker, currentFile, targetClass)
        // Same qualifier-source split as collectScopeNewDef: for a plain function reference
        // (singleOf(::dispatcherIO)), a custom qualifier annotation lives on the FUNCTION itself
        // (e.g. @Dispatcher(NiaDispatchers.IO) fun dispatcherIO(): CoroutineDispatcher), never on
        // its return type's class — extractFromClass(targetClass) would read CoroutineDispatcher
        // (never annotated) and silently register this provider unqualified, colliding two
        // differently-qualified singleOf registrations of the same type into one (a real bug found
        // via the app-dsl playground's own DispatchersModule, same shape as 4db7c11's regression).
        val qualifier = when (referencedFunction) {
            is IrConstructor -> qualifierExtractor.extractFromClass(targetClass)
            is IrSimpleFunction -> qualifierExtractor.extractFromDeclaration(referencedFunction, "function ${referencedFunction.name}")
        }
        _dslDefinitions.add(buildDslDef(targetClass, defType, qualifier) {
            requirementsFor(referencedFunction)
        })
        KoinPluginLogger.user { "Intercepting ${expression.symbol.owner.name}(::${targetClass.name}) (constructor-shorthand DSL)" }
    }

    /**
     * SAFETY helper. What DI actually resolves to invoke a `::Ctor`/`::function` reference — the
     * referenced declaration's OWN parameters, never the returned class's constructor (a plain
     * function may take unrelated params, or none, regardless of what its return type's
     * constructor needs). Shared by create(::T) (both branches in handleScopeCreate) and
     * collectScopeNewDef so this derivation can't drift between call sites again.
     */
    private fun requirementsFor(referencedFunction: IrFunction): List<Requirement> = when (referencedFunction) {
        is IrConstructor -> parameterAnalyzer.analyzeConstructor(referencedFunction)
        is IrSimpleFunction -> parameterAnalyzer.analyzeFunction(referencedFunction)
    }

    /**
     * SAFETY helper. Build a DslDef for [providedClass] under the current [transformContext]
     * (scope/module id), with A3 metadata attached. Shared shape for every DSL definition
     * collection site in this file — called both from dedicated `collect*` functions and inline
     * from the `handle*` codegen functions that resolve the same data on their way past.
     */
    private fun buildDslDef(
        providedClass: IrClass,
        defType: DefinitionType,
        qualifier: QualifierValue?,
        providerOnly: Boolean = false,
        requirements: () -> List<Requirement>,
    ): Definition.DslDef = Definition.DslDef(
        irClass = providedClass,
        definitionType = defType,
        bindings = emptyList(), // DSL: only explicit bind() adds bindings
        scopeClass = if (defType == DefinitionType.SCOPED) transformContext.scopeTypeClass else null,
        modulePropertyId = transformContext.modulePropertyId,
        providerOnly = providerOnly,
        qualifier = qualifier,
        registrationSourceFile = currentFile
    ).attachA3Metadata(providedClass, requirements)

    /** SHARED helper (CODEGEN reads it for the rewritten call's qualifier arg; SAFETY reads it for
     *  the collected DslDef's qualifier). Resolve the `qualifier` value argument (by parameter
     *  name) of a Koin DSL definition call, if present. */
    private fun extractQualifierArgument(call: IrCall, callee: IrSimpleFunction): QualifierValue? {
        val qualifierIndex = callee.regularParameters.indexOfFirst { it.name.asString() == "qualifier" }
        if (qualifierIndex < 0) return null
        return qualifierExtractor.extractFromExpression(call.getRegularArgument(qualifierIndex))
    }

    /**
     * SAFETY ONLY — no codegen. Collects the requirements for Koin's own `Scope.new(::Constructor)`
     * / `Scope.new(::function)` (org.koin.core.module.dsl, 0..22-arity reified overloads) — the
     * requirement-registration half of [handleScopeCreate], MINUS the codegen half (hence
     * `collect*`, not `handle*`, despite the parallel to handleScopeCreate below).
     *
     * `create(::T)` is THIS PLUGIN'S OWN invented DSL stub: Koin has no real `Scope.create`, so
     * [handleScopeCreate] must fully rewrite the call into `T(get(), get(), ...)`. `new(::T)` is the
     * opposite — a REAL Koin library function whose inline body already does exactly that
     * (`constructor(get(), get(), ...)`) using Koin's own runtime resolution. It works correctly with
     * zero help from this plugin; the call is left untouched (the caller passes the untransformed
     * `transformedCall` through — see the `new(::T)` branch in visitCall).
     *
     * What it does NOT get for free is compile-time validation: without this, `single<T> { new(::T) }`
     * fell into the generic "opaque lambda body" fallback a few lines up (providerOnly = true, empty
     * requirements) — the exact same bucket as a truly opaque call like `single<T> { someFactory() }`.
     * But unlike that case, `new(::T)`'s dependencies ARE statically known (same function-reference
     * shape `create(::T)` already extracts them from) — so silently assuming zero requirements was a
     * needless false negative: a real missing dependency inside T's constructor compiled green and
     * only surfaced as a runtime NoDefinitionFoundException. Worst failure class per this project's
     * doctrine (silent > broken).
     */
    private fun collectScopeNewDef(referencedFunction: IrFunction) {
        val enclosingDefType = currentDefinitionCall?.let { definitionTypeMap[it] } ?: return
        if (!compileSafetyEnabled) return
        when (referencedFunction) {
            is IrConstructor -> {
                val targetClass = referencedFunction.parent as IrClass
                trackClassLookup(lookupTracker, currentFile, targetClass)
                linkDeclarationsForIC(expectActualTracker, currentFile, targetClass)
                val qualifier = transformContext.definitionQualifier ?: qualifierExtractor.extractFromClass(targetClass)
                // Same typed-enclosing rule as create(::T): `single<Interface> { new(::Impl) }`
                // registers Interface, not Impl — runtime Koin registers the outer type.
                val providedClass = transformContext.definitionCallTypeArg ?: targetClass
                _dslDefinitions.add(buildDslDef(providedClass, enclosingDefType, qualifier) { requirementsFor(referencedFunction) })
                KoinPluginLogger.user { "Intercepting ${currentDefinitionCall?.asString()} { new(::${targetClass.name}) } -> ${providedClass.name}" }
            }
            is IrSimpleFunction -> {
                val returnTypeClass = referencedFunction.returnType.classifierOrNull?.owner as? IrClass ?: return
                trackClassLookup(lookupTracker, currentFile, returnTypeClass)
                linkDeclarationsForIC(expectActualTracker, currentFile, returnTypeClass)
                val qualifier = transformContext.definitionQualifier
                    ?: qualifierExtractor.extractFromDeclaration(referencedFunction, "function ${referencedFunction.name}")
                val providedClass = transformContext.definitionCallTypeArg ?: returnTypeClass
                _dslDefinitions.add(buildDslDef(providedClass, enclosingDefType, qualifier) { requirementsFor(referencedFunction) })
                KoinPluginLogger.user { "Intercepting ${currentDefinitionCall?.asString()} { new(::${referencedFunction.name}) } -> ${providedClass.name}" }
            }
        }
    }

    /**
     * CODEGEN, with inline SAFETY steps in both branches: rewrites Scope.create(::Constructor) or
     * Scope.create(::function) —
     *   Constructor -> Constructor(get(), get(), ...)
     *   Function -> function(get(), get(), ...)
     * Each branch resolves a qualifier for TWO reasons at once: CODEGEN needs it propagated to
     * `transformContext.createQualifier` so the enclosing `single { create(::T) }` call rewrites
     * with the right qualifier (see handleDefinitionWithCreateQualifier); SAFETY needs the exact
     * same value on the collected DslDef. Computed once, used both ways — see each branch's
     * `// SAFETY:` / `// CODEGEN:` comments for which line is which.
     */
    private fun handleScopeCreate(
        call: IrCall,
        referencedFunction: IrFunction,
        scopeReceiver: IrExpression
    ): IrExpression {
        // Validate that create() is the only instruction in the lambda (if enabled)
        if (unsafeDslChecksEnabled) {
            validateCreateInLambda(call, referencedFunction)
        }

        val builder = DeclarationIrBuilder(context, call.symbol, call.startOffset, call.endOffset)

        return when (referencedFunction) {
            is IrConstructor -> {
                val targetClass = referencedFunction.parent as IrClass
                // IC: file containing create(::T) depends on the target class
                trackClassLookup(lookupTracker, currentFile, targetClass)
                linkDeclarationsForIC(expectActualTracker, currentFile, targetClass)
                // Extract qualifier from class — shared by CODEGEN and SAFETY, see this
                // function's kdoc.
                val classQualifier = transformContext.definitionQualifier ?: qualifierExtractor.extractFromClass(targetClass)
                // CODEGEN: propagate to the enclosing definition call (single { create(::T) }).
                if (classQualifier != null && currentDefinitionCall != null) {
                    transformContext = transformContext.copy(createQualifier = classQualifier, createReturnClass = targetClass)
                }
                // SAFETY: collect the DslDef from create(::T) based on the enclosing definition
                // call. When the enclosing call is typed (e.g. `single<Interface> { create(::Impl) }`),
                // the provided type is the outer `<T>`, not the create target — runtime Koin
                // registers `T` and the impl is just a construction detail.
                val enclosingDefType = currentDefinitionCall?.let { definitionTypeMap[it] }
                val providedClass = transformContext.definitionCallTypeArg ?: targetClass
                if (enclosingDefType != null && compileSafetyEnabled) {
                    _dslDefinitions.add(buildDslDef(providedClass, enclosingDefType, classQualifier) { requirementsFor(referencedFunction) })
                }
                val enclosingDef = currentDefinitionCall?.asString() ?: "unknown"
                KoinPluginLogger.user { "Intercepting $enclosingDef { create(::${targetClass.name}) } -> ${providedClass.name}" }
                // ── back to CODEGEN: build the actual Constructor(get(), get(), ...) call ──
                builder.irCallConstructor(referencedFunction.symbol, emptyList()).apply {
                    referencedFunction.regularParameters.forEachIndexed { index, param ->
                        val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeReceiver, null, builder)
                        if (argument != null) {
                            putRegularArgument(index, argument)
                        }
                        // If argument is null, parameter has a default value and will use it
                    }
                }
            }
            is IrSimpleFunction -> {
                // Extract qualifier from function — shared by CODEGEN and SAFETY, see this
                // function's kdoc.
                val returnTypeClass = referencedFunction.returnType.classifierOrNull?.owner as? IrClass
                val funcQualifier = transformContext.definitionQualifier
                    ?: qualifierExtractor.extractFromDeclaration(referencedFunction, "function ${referencedFunction.name}")
                // CODEGEN: propagate to the enclosing definition call.
                if (funcQualifier != null && currentDefinitionCall != null) {
                    transformContext = transformContext.copy(
                        createQualifier = funcQualifier,
                        createReturnClass = returnTypeClass
                    )
                }
                // SAFETY: collect the DslDef. Same typed-enclosing rule as the constructor branch:
                // `single<T> { create(::func) }` registers T, not the function's return type.
                val enclosingDefType = currentDefinitionCall?.let { definitionTypeMap[it] }
                val providedClass = transformContext.definitionCallTypeArg ?: returnTypeClass
                if (enclosingDefType != null && compileSafetyEnabled && providedClass != null) {
                    trackClassLookup(lookupTracker, currentFile, providedClass)
                    linkDeclarationsForIC(expectActualTracker, currentFile, providedClass)
                    _dslDefinitions.add(buildDslDef(providedClass, enclosingDefType, funcQualifier) { requirementsFor(referencedFunction) })
                }
                val returnTypeName = referencedFunction.returnType.classFqName?.shortName() ?: referencedFunction.returnType.toString()
                val enclosingDef = currentDefinitionCall?.asString() ?: "unknown"
                KoinPluginLogger.user { "Intercepting $enclosingDef { create(::${referencedFunction.name}) } -> $returnTypeName" }
                // ── back to CODEGEN: build the actual function(get(), get(), ...) call ──
                builder.irCall(referencedFunction.symbol).apply {
                    referencedFunction.regularParameters.forEachIndexed { index, param ->
                        val argument = argumentGenerator.generateKoinArgumentForParameter(param, scopeReceiver, null, builder)
                        if (argument != null) {
                            putRegularArgument(index, argument)
                        }
                        // If argument is null, parameter has a default value and will use it
                    }
                }
            }
        }
    }

    /**
     * CODEGEN ONLY (no safety collection — that already happened in handleScopeCreate, which set
     * `transformContext.createQualifier` to reach here). Handle single/factory/etc
     * { create(::ref) } where ::ref has a qualifier annotation: replaces the definition call with
     * buildSingle/buildFactory/etc.(KClass, qualifier) { body } so the qualifier is properly
     * registered with the definition.
     */
    private fun handleDefinitionWithCreateQualifier(
        call: IrCall,
        receiver: IrExpression,
        receiverClassifier: IrClass,
        functionName: Name,
        returnClass: IrClass,
        qualifier: QualifierValue
    ): IrExpression {
        val receiverClassName = receiverClassifier.name.asString()
        val targetFunction = findTargetFunction(functionName, receiverClassName)
        if (targetFunction == null) {
            KoinPluginLogger.debug { "$functionName target function not found for $receiverClassName (qualifier propagation)" }
            return call
        }

        // Find the lambda argument from the original call
        val existingLambda = (0 until call.regularArgumentsCount)
            .mapNotNull { call.getRegularArgument(it) }
            .firstOrNull { it is IrFunctionExpression }
            ?: return call

        KoinPluginLogger.user { "Applying qualifier ${qualifier.debugString()} to $functionName { create(::${returnClass.name}) }" }

        val builder = DeclarationIrBuilder(context, call.symbol, call.startOffset, call.endOffset)

        return builder.irCall(targetFunction.symbol).apply {
            setExtensionReceiverArgument(receiver)
            putTypeArgumentCompat(0, returnClass.defaultType)

            // Arg 0: KClass<T>
            val kClassClassOwner = kClassClass ?: return call
            putRegularArgument(0, IrClassReferenceImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                kClassClassOwner.typeWith(returnClass.defaultType),
                returnClass.symbol,
                returnClass.defaultType
            ))

            // Arg 1: Qualifier
            putRegularArgument(1, qualifierExtractor.createQualifierCall(qualifier, builder) ?: builder.irNull())

            // Arg 2: Existing definition lambda (already transformed by super.visitCall)
            putRegularArgument(2, existingLambda)
        }
    }

    /**
     * CODEGEN-ADJACENT (KOIN-S001, gated by `unsafeDslChecks`, unrelated to A3 compile-safety).
     * Validates that create() is the only instruction in the enclosing lambda — codegen for
     * create(::T) assumes this shape and would silently drop other statements otherwise.
     * Reports a compilation error if there are other statements.
     */
    private fun validateCreateInLambda(call: IrCall, referencedFunction: IrFunction) {
        val lambda = currentLambda ?: return  // Not inside a lambda, no validation needed

        val body = lambda.body as? IrBlockBody ?: return
        val statements = body.statements

        // A valid lambda body should have exactly one statement: a return with the create() call
        // or the create() call as an implicit return expression
        val isValid = when {
            statements.size == 1 -> {
                val stmt = statements[0]
                when (stmt) {
                    is IrReturn -> isCreateCall(stmt.value, call)
                    is IrCall -> isCreateCall(stmt, call)
                    else -> false
                }
            }
            else -> false
        }

        if (!isValid) {
            val targetName = when (referencedFunction) {
                is IrConstructor -> (referencedFunction.parent as IrClass).name.asString()
                is IrSimpleFunction -> referencedFunction.name.asString()
            }
            KoinPluginLogger.report(KoinDiagnostic.UnsafeDsl(target = targetName))
        }
    }

    /**
     * Checks if the given expression is the create() call we're validating.
     */
    private fun isCreateCall(expr: IrExpression?, targetCall: IrCall): Boolean {
        return expr === targetCall
    }

    /** CODEGEN ONLY. Resolves the real `build*` function a stub call rewrites into. */
    private fun findTargetFunction(functionName: Name, receiverClassName: String): IrSimpleFunction? {
        // Map stub function name to target function name (e.g., single -> buildSingle)
        val targetName = targetFunctionNames[functionName] ?: return null

        // Check cache first
        val cacheKey = functionName to receiverClassName
        targetFunctionCache[cacheKey]?.let { return it }

        val functions = context.referenceFunctions(
            CallableId(FqName("org.koin.plugin.module.dsl"), targetName)
        )
        val result = functions
            .map { it.owner }
            .filterIsInstance<IrSimpleFunction>()
            .firstOrNull { function ->
                function.extensionReceiverParam?.type?.classifierOrNull?.owner?.let {
                    (it as? IrClass)?.name?.asString() == receiverClassName
                } == true &&
                function.regularParameters.size >= 3 &&
                function.regularParameters[0].type.classifierOrNull?.owner?.let {
                    (it as? IrClass)?.name?.asString() == "KClass"
                } == true
            }

        // Cache the result (including null)
        targetFunctionCache[cacheKey] = result
        return result
    }

}

/**
 * A pending call-site validation collected during Phase 2.
 * Validated after Phase 3 when the assembled graph is available.
 *
 * Two-field encoding of `parametersOf(...)` state so KOIN-D005 and KOIN-D006 can fire
 * independently:
 *
 * @property hasParametersLambda `true` iff the user passed a trailing lambda for the
 *   `parameters: (() -> ParametersHolder)?` slot. Used by KOIN-D006: if a def needs
 *   `@InjectedParam` but this is `false`, the user forgot `parametersOf(...)` entirely.
 *
 * @property parametersOfArgs Positional args captured when the trailing lambda contains a
 *   single statically detectable `parametersOf(...)` call. `null` means either no lambda
 *   was passed OR the lambda was non-trivial (`{ buildHolder() }`, `{ if (...) … }`, etc.) —
 *   in the latter case shape checks (KOIN-D005) are skipped to avoid false positives.
 *   Empty list means `parametersOf()` (no args). Items whose `typeFqName == null` and
 *   `isNullable == false` represent args we couldn't classify; presence of any such arg
 *   makes the whole call ambiguous downstream.
 */
data class PendingCallSiteValidation(
    val targetFqName: String,
    val targetClass: IrClass,
    val callFunctionName: String,
    val filePath: String?,
    val line: Int,
    val column: Int,
    val hasParametersLambda: Boolean = false,
    val parametersOfArgs: List<BindingRegistry.Companion.ParametersOfArg>? = null,
)
