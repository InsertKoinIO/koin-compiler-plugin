# Koin Compiler Plugin — Complete Code Walkthrough

> **Version:** 0.6.3 | **Date:** 2026-04-03
>
> Line-by-line debugging reference for the Koin maintainer.
> Every file, every method, every data flow.

---

## Table of Contents

1. [Plugin Bootstrap](#1-plugin-bootstrap)
2. [Global State](#2-global-state)
3. [FIR Phase — Declaration Generation](#3-fir-phase)
4. [IR Phase 0 — Hint Body Generation](#4-ir-phase-0)
5. [IR Phase 1 — Annotation Processing](#5-ir-phase-1)
6. [IR Phase 2 — DSL Transformation](#6-ir-phase-2)
7. [IR Phase 2.5 — DSL Hint Generation](#7-ir-phase-25)
8. [IR Phase 3 — startKoin Transformation](#8-ir-phase-3)
9. [IR Phase 3.1 — DSL-only A3 Validation](#9-ir-phase-31)
10. [IR Phase 3.5 — Call-site Validation](#10-ir-phase-35)
11. [IR Phase 3.6 — Cross-module Call-site Hints](#11-ir-phase-36)
12. [IR Phase 4 — @Monitor Transformation](#12-ir-phase-4)
13. [Helper Classes Reference](#13-helper-classes)
14. [Data Model Reference](#14-data-models)
15. [Hint Function Naming Convention](#15-hint-naming)
16. [Validation Layers](#16-validation-layers)

---

## 1. Plugin Bootstrap

### `KoinPluginComponentRegistrar` (KoinPluginComponentRegistrar.kt:162)

**SPI entry point.** Kotlin compiler discovers this via `META-INF/services`.

```
registerExtensions(configuration):
  L170: messageCollector = configuration.get(MESSAGE_COLLECTOR_KEY)
  L171: userLogs = configuration.get(USER_LOGS, false)
  L172: debugLogs = configuration.get(DEBUG_LOGS, false)
  L173: unsafeDslChecks = configuration.get(UNSAFE_DSL_CHECKS, true)
  L174: skipDefaultValues = configuration.get(SKIP_DEFAULT_VALUES, true)
  L175: compileSafety = configuration.get(COMPILE_SAFETY, true)
  L178: lookupTracker = configuration.get(LOOKUP_TRACKER)   // IC support
  L181: KoinPluginLogger.init(...)                           // Global singleton
  L182: expectActualTracker = configuration.get(EXPECT_ACTUAL_TRACKER)
  L189: FirExtensionRegistrarAdapter.registerExtension(KoinPluginRegistrar())
  L191: IrGenerationExtension.registerExtension(KoinIrExtension(lookupTracker, expectActualTracker))
```

### `KoinPluginRegistrar` (KoinPluginRegistrar.kt:7)

Registers two FIR extensions:
```
configurePlugin():
  L9:  +::KoinModuleFirGenerator      // Declaration generation
  L10: +::FirKoinLookupRecorder       // IC dependency tracking
```

---

## 2. Global State

### `KoinPluginLogger` (KoinPluginComponentRegistrar.kt:29)

Global `object`. All fields `@Volatile` for Gradle daemon parallel builds.

| Field | Type | Default | Set by |
|-------|------|---------|--------|
| `messageCollector` | `MessageCollector` | `NONE` | `init()` |
| `userLogsEnabled` | `Boolean` | `false` | `init()` |
| `debugLogsEnabled` | `Boolean` | `false` | `init()` |
| `unsafeDslChecksEnabled` | `Boolean` | `true` | `init()` |
| `skipDefaultValuesEnabled` | `Boolean` | `true` | `init()` |
| `compileSafetyEnabled` | `Boolean` | `true` | `init()` |
| `lookupTracker` | `LookupTracker?` | `null` | `init()` |

**Logging methods** (all `inline` — lambda never invoked if disabled):

| Method | Prefix | Condition |
|--------|--------|-----------|
| `user {}` | `[Koin]` | `userLogsEnabled` |
| `debug {}` | `[Koin-Debug]` | `debugLogsEnabled` |
| `warn(msg)` | `[Koin]` | **always** |
| `error(msg)` | `[Koin]` | **always**, severity=ERROR |
| `error(msg, file, line, col)` | `[Koin]` | **always**, with `CompilerMessageLocation` |
| `userFir {}` | `[Koin-FIR]` | `userLogsEnabled` |
| `debugFir {}` | `[Koin-Debug-FIR]` | `debugLogsEnabled` |

### `PropertyValueRegistry` (PropertyValueRegistry.kt:22)

```kotlin
object PropertyValueRegistry {
    private val propertyDefaults = ConcurrentHashMap<String, IrProperty>()
    fun register(propertyKey: String, property: IrProperty)  // Called by Phase 1 for @PropertyValue
    fun getDefault(propertyKey: String): IrProperty?         // Called by KoinArgumentGenerator
    fun hasDefault(propertyKey: String): Boolean              // Called by BindingRegistry validation
    fun clear()                                               // Called at start of Phase 1
}
```

### `ProvidedTypeRegistry` (ProvidedTypeRegistry.kt:21)

```kotlin
object ProvidedTypeRegistry {
    private val providedTypes: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun register(fqName: String)       // Called by Phase 1 for @Provided on class
    fun isProvided(fqName: String)     // Called by BindingRegistry, CallSiteValidator
    fun clear()                         // Called at start of Phase 1
}
```

### `KoinPluginConstants` (KoinPluginConstants.kt:9)

All `const val`. Key constants:

```
DEF_TYPE_SINGLE = "single"
DEF_TYPE_FACTORY = "factory"
DEF_TYPE_SCOPED = "scoped"
DEF_TYPE_VIEWMODEL = "viewmodel"
DEF_TYPE_WORKER = "worker"

HINTS_PACKAGE = "org.koin.plugin.hints"
HINT_FUNCTION_PREFIX = "configuration_"
DEFINITION_HINT_PREFIX = "definition_"
DEFINITION_FUNCTION_HINT_PREFIX = "definition_function_"
COMPONENT_SCAN_HINT_PREFIX = "componentscan_"
COMPONENT_SCAN_FUNCTION_HINT_PREFIX = "componentscanfunc_"
MODULE_DEFINITION_HINT_PREFIX = "moduledef_"
DSL_DEFINITION_HINT_PREFIX = "dsl_"
QUALIFIER_HINT_NAME = "qualifier"
CALLSITE_HINT_NAME = "callsite"
DSL_MODULE_PARAM_PREFIX = "module_"
DEFAULT_LABEL = "default"
MODULE_FUNCTION_NAME = "module"
```

### `KoinAnnotationFqNames` (KoinAnnotationFqNames.kt:11)

All annotation FqNames. Key groups:

- **Module**: `MODULE`, `COMPONENT_SCAN`, `CONFIGURATION`
- **Definition**: `SINGLETON`, `SINGLE`, `FACTORY`, `SCOPED`, `KOIN_VIEW_MODEL`, `KOIN_WORKER`
- **Scope**: `SCOPE`, `VIEW_MODEL_SCOPE`, `ACTIVITY_SCOPE`, `ACTIVITY_RETAINED_SCOPE`, `FRAGMENT_SCOPE`
- **Parameter**: `NAMED`, `QUALIFIER`, `INJECTED_PARAM`, `PROPERTY`, `PROPERTY_VALUE`, `PROVIDED`, `SCOPE_ID`
- **App**: `KOIN_APPLICATION`
- **Monitor**: `MONITOR`, `KOTZILLA_CORE`
- **JSR-330**: `JAKARTA_SINGLETON`, `JAKARTA_INJECT`, `JAKARTA_NAMED`, `JAKARTA_QUALIFIER`, `JAVAX_*`
- **Core classes**: `KOIN_MODULE`, `SCOPE_CLASS`, `PARAMETERS_HOLDER`, `MODULE_DSL`, `SCOPE_DSL`, `PLUGIN_MODULE_DSL`
- **Call-site functions**: `CALL_SITE_RESOLUTION_FUNCTIONS` (22 entries: compose, android, ktor)

---

## 3. FIR Phase

### `KoinModuleFirGenerator` (KoinModuleFirGenerator.kt:85)

**Extends `FirDeclarationGenerationExtension`.** Generates function declarations with null bodies.

#### 3.1 Discovery (Lazy Properties)

All discovery is lazy — evaluated once on first access.

**`moduleClassInfos`** (L622): `@Module` classes
```
predicateBasedProvider.getSymbolsByPredicate(modulePredicate)
  → filter out expect classes
  → capture containingFileName from source (PSI or synthetic)
  → skip dependency classes (null source)
  → returns List<ModuleClassInfo(classSymbol, containingFileName)>
```

Source type detection (L645-665):
- `KtPsiSourceElement` → `source.psi.containingFile.name` (JVM, standard)
- `RealSourceElementKind` → `syntheticFileName(classId, "Module")` (KMP: Native/JS/Wasm)
- Other → `null` → skip (dependency from JAR)

**`configurationModules`** (L679): `@Module + @Configuration`
```
predicateBasedProvider.getSymbolsByPredicate(configurationPredicate) → configClassIds
moduleClassInfos.filter { classId in configClassIds || coneType annotation found }
  → extract labels from @Configuration vararg
  → default label: ["default"]
  → returns List<ConfigurationModule(classSymbol, labels, containingFileName)>
```

**`localScanPackages`** (L805): Packages from `@ComponentScan`
```
predicateBasedProvider.getSymbolsByPredicate(componentScanPredicate) → scanClassIds
moduleClassInfos.filter { classId in scanClassIds }
  → extract packages from @ComponentScan args (or default to class package)
  → returns Set<String>
```

**`definitionClassInfos`** (L893): Orphan definition classes
```
For each predicate (singleton, single, factory, scoped, viewModel, worker,
    viewModelScope, activityScope, activityRetainedScope, fragmentScope,
    jakartaSingleton, javaxSingleton, jakartaInject, javaxInject):
  predicateBasedProvider.getSymbolsByPredicate(predicate)
    → filter out expect classes
    → filter out classes covered by localScanPackages (isCoveredByLocalScan)
    → capture containingFileName
    → add to definitions list

Then: collectInjectConstructorClasses(definitions)
  → PSI scan for @Inject on constructor (not detectable by predicates)
  → scan files from known classes, then fallback via anyClassPredicate
```

**`definitionFunctionInfos`** (L951): Orphan top-level functions
```
Same pattern as definitionClassInfos but for FirNamedFunctionSymbol
  → skip functions inside classes (callableId.classId != null)
  → skip Unit return types
  → skip functions covered by localScanPackages
  → extract metadata: qualifierName, qualifierTypeClassId, scopeClassId, bindingClassIds
```

**`moduleDefinitionFunctionInfos`** (L1025): Functions inside @Module classes
```
Same predicates, but filter by containingClassId in moduleClassIds
  → each function generates its own hint for per-function ABI tracking
  → extract metadata: qualifier, scope, bindings
```

**`qualifierAnnotationInfos`** (L1107): Custom qualifier annotations
```
predicateBasedProvider.getSymbolsByPredicate(qualifierAnnotationPredicate)
  → filter for ANNOTATION_CLASS kind, not expect
```

#### 3.2 Predicate Registration

`registerPredicates()` (L1317): Registers ALL predicates with the FIR system.

#### 3.3 Callable ID Generation

`getTopLevelCallableIds()` (L1348): Returns all CallableIds that will be generated.

```
For each @Module class:
  CallableId(packageFqName, "module")

For each configuration label:
  CallableId(HINTS_PACKAGE, "configuration_<label>")

Always:
  CallableId(HINTS_PACKAGE, "configuration_default")

For each defType in [single, factory, scoped, viewmodel, worker]:
  CallableId(HINTS_PACKAGE, "definition_<type>")
  CallableId(HINTS_PACKAGE, "definition_function_<type>")

If qualifierAnnotationInfos not empty:
  CallableId(HINTS_PACKAGE, "qualifier")

For each module definition function:
  CallableId(HINTS_PACKAGE, "moduledef_<moduleId>__<funcName>")
```

#### 3.4 Function Generation

`generateFunctions(callableId, context)` (L1414): Creates FIR function declarations.

**Configuration hints** (L1420-1448):
```
if callableId in HINTS_PACKAGE && matches "configuration_<label>":
  for each ConfigurationModule with matching label:
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", moduleClassType)
    }.markAsDeprecatedHidden()
```

**Module extension functions** (L1452-1490):
```
if callableId.callableName == "module":
  for each ModuleClassInfo in matching package:
    createTopLevelFunction(Key, callableId, KoinModule) {
      extensionReceiverType(moduleClassType)
    }
```

**Definition hints** (L1494-1527):
```
if callableId in HINTS_PACKAGE && matches "definition_<type>":
  for each DefinitionClassInfo with matching defType:
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", classType)
    }.markAsDeprecatedHidden()
```

**Function definition hints** (L1533-1576):
```
if callableId in HINTS_PACKAGE && matches "definition_function_<type>":
  for each DefinitionFunctionInfo with matching defType:
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", returnClassType)
      // metadata params: binding0, binding1, scope, qualifier_<name>, qualifierType, funcpkg_<pkg>
    }.markAsDeprecatedHidden()
```

**Module definition hints** (L1579-1608):
```
if callableId in HINTS_PACKAGE && matches "moduledef_<moduleId>__<funcName>":
  find matching ModuleDefinitionFunctionInfo
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", returnClassType)
      // metadata params
    }.markAsDeprecatedHidden()
```

**Qualifier hints** (L1614-1630):
```
if callableId == "qualifier":
  for each QualifierAnnotationInfo:
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", qualifierAnnotationType)
    }.markAsDeprecatedHidden()
```

### `FirKoinLookupRecorder` (FirKoinLookupRecorder.kt:40)

**Extends `FirAdditionalCheckersExtension`.** IC dependency recording.

```
KoinApplicationLookupChecker.check(declaration):
  if !declaration.hasAnnotation(@KoinApplication): return
  lookupTracker = KoinPluginLogger.lookupTracker ?: return
  filePath = context.containingFilePath ?: return
  labels = extractConfigurationLabels(declaration)

  for label in labels:
    hintFunctions = session.symbolProvider.getTopLevelFunctionSymbols(
      HINTS_PACKAGE, "configuration_<label>")
    for hintFunc in hintFunctions:
      moduleClassId = hintFunc.valueParameterSymbols[0].resolvedReturnType.classId
      lookupTracker.record(filePath, NO_POSITION, moduleClassId.packageFqName, PACKAGE, shortClassName)
```

---

## 4. IR Phase 0 — Hint Body Generation

### `KoinHintTransformer` (KoinHintTransformer.kt:32)

**Extends `IrElementTransformerVoid`.** Fills FIR-generated hint functions with bodies.

```
visitSimpleFunction(declaration):
  fqName = declaration.fqNameWhenAvailable
  parentPackage = fqName?.parent()
  functionName = declaration.name.asString()

  isConfigurationHint = parentPackage == HINTS_PACKAGE && labelFromHintFunctionName(functionName) != null
  isDefinitionHint = parentPackage == HINTS_PACKAGE && definitionTypeFromHintFunctionName(functionName) != null
  isFunctionDefinitionHint = parentPackage == HINTS_PACKAGE && definitionTypeFromFunctionHintName(functionName) != null
  isModuleDefinitionHint = parentPackage == HINTS_PACKAGE && moduleDefinitionInfoFromHintName(functionName) != null
  isQualifierHint = parentPackage == HINTS_PACKAGE && functionName == "qualifier"
  // Note: componentscan_* / componentscanfunc_* generated in IR with non-null bodies → NOT processed here

  if (isAnyHint && declaration.body == null):
    declaration.body = generateHintFunctionBody(declaration)  // error("Stub!")
    context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(declaration)  // CRITICAL
```

**`generateHintFunctionBody`** (L75):
```
builder.irBlockBody {
  +irCall(kotlin.error).apply { putValueArgument(0, irString("Stub!")) }
}
```

---

## 5. IR Phase 1 — Annotation Processing

### `KoinAnnotationProcessor` (KoinAnnotationProcessor.kt)

**NOT a transformer.** Called directly from `KoinIrExtension`.

#### 5.1 `collectAnnotations(moduleFragment)` — Single tree walk

Visits all declarations:

**For classes:**
```
processClass(declaration):
  if @Module:
    hasComponentScan = has @ComponentScan
    scanPackages = extract from @ComponentScan args (or class package)
    definitionFunctions = member functions with @Singleton/@Factory/@Scoped/@KoinViewModel/@KoinWorker
    includedModules = @Module(includes = [A::class, B::class])
    → add to moduleClasses list

  if @Singleton/@Factory/@Scoped/@KoinViewModel/@KoinWorker:
    definitionType = map annotation → DefinitionType
    bindings = getExplicitBindings(declaration) ?: detectBindings(declaration)
    scopeClass = @Scope(MyScope::class)
    scopeArchetype = @ViewModelScope / @ActivityScope / etc.
    createdAtStart = @Single(createdAtStart = true)
    → add to definitionClasses list

  if @Provided:
    ProvidedTypeRegistry.register(fqName)
```

**`getExplicitBindings(declaration)`**: Returns `List<IrClass>?`
- `null` = no `binds` param → auto-detect bindings
- `emptyList` = explicit `binds = []` → NO auto-binding (fixes delegation pattern)
- `[A, B]` = explicit `binds = [A::class, B::class]`

**For properties:**
```
processPropertyValue(declaration):
  if @PropertyValue("key"):
    PropertyValueRegistry.register(key, property)
```

**For top-level functions:**
```
processTopLevelFunction(declaration):
  if @Singleton/@Factory/etc.:
    returnTypeClass = extract from return type
    → add to definitionTopLevelFunctions list
```

#### 5.2 `generateModuleExtensions(moduleFragment)` — Two passes

**Pass 1: Collect + Validate + Generate Hints**

```
for each moduleClass in moduleClasses:
  // Step 1: Collect all definitions
  allDefinitions = collectAllDefinitions(moduleClass)
    // = local class defs + local function defs + cross-module hints
    // Uses: definitionClasses filtered by scanPackages
    //       + discoverDefinitionsFromHints(componentscan_*)
    //       + discoverFunctionDefinitionsFromHints(componentscanfunc_*)
    //       + moduleClass.definitionFunctions

  // Step 1b: Generate module-scan hints (batched)
  generateModuleScanHints(moduleFragment, moduleDefinitions)
    // For each @Configuration module with @ComponentScan:
    //   Collect all hint functions into hintFunctions list
    //   Create ONE synthetic IrFile per module
    //   Register each function as metadata-visible

  // Step 2: Build visibility
  visibleDefinitions = buildVisibleDefinitions(moduleClass, allDefinitions, allModuleDefinitions)
    // = own definitions
    // + included module definitions (recursive)
    // + @Configuration sibling module definitions (from hints)

  // Step 3: A2 validation
  if safetyValidator != null:
    safetyValidator.validate(moduleName, moduleFqName, ownDefinitions, visibleDefinitions)

  // Store function reference for Pass 2
  moduleFunctions[moduleClass] = findOrCreateModuleFunction(moduleClass)
```

**Pass 2: Fill Function Bodies**

```
for each (moduleClass, function) in moduleFunctions:
  fillFunctionBody(function, moduleClass)
```

**`fillFunctionBody(function, moduleClass)`**:
```
Find org.koin.dsl.module() function
Build lambda: Module.() -> Unit
  For each definition:
    if definition has scope or scopeArchetype:
      buildScopeBlock / buildArchetypeScopeBlock { scoped { ... } }
    else:
      buildSingle/buildFactory/buildScoped/buildViewModel/buildWorker call:
        arg 0: KClass<T> reference
        arg 1: qualifier (named("x") / typeQualifier<T>() / null)
        arg 2: definition lambda (see LambdaBuilder)
  For includes:
    module.includes(Mod1().module(), Mod2().module())
Build: module { ... lambda ... }
```

---

## 6. IR Phase 2 — DSL Transformation

### `KoinDSLTransformer` (KoinDSLTransformer.kt)

**Extends `IrElementTransformerVoid`.** Transforms DSL calls in user code.

#### 6.1 Context Stack

```kotlin
data class TransformContext(
    val function: IrFunction? = null,          // Current function
    val lambda: IrSimpleFunction? = null,      // Current lambda
    val definitionCall: Name? = null,          // "single", "factory", etc.
    val scopeTypeClass: IrClass? = null,       // scope<ScopeType> { }
    val createQualifier: QualifierValue? = null, // Propagated from create(::ref)
    val createReturnClass: IrClass? = null,    // Return class from create()
    val modulePropertyId: String? = null       // Module property ID for DSL tracing
)
```

`withContext(newContext, block)` — save/restore pattern. After block, checks if inner context set `createQualifier` and propagates upward.

#### 6.2 `visitCall(expression)`

**Call-site collection** (first):
```
if callee FqName in CALL_SITE_RESOLUTION_FUNCTIONS:
  Extract type argument T, target class, location (file/line/col)
  → add to _pendingCallSites
  Record IC lookup dependency
```

**Module loading info** (second):
```
collectModuleLoadingInfo(expression, callee):
  if Module.includes(): record module→module mapping → _moduleIncludes
  if KoinApplication.modules(): record loaded modules → _startKoinModules
```

**Scope tracking**:
```
if callee is Module.scope<ScopeType> { }:
  Push TransformContext with scopeTypeClass
```

**DSL transformation** (main):
```
if callee is Module.single/factory/scoped/viewModel/worker:
  → handleTypeParameterCall(call, extensionReceiver, receiverClassifier, functionName)

if callee is Scope.create(::T):
  → handleScopeCreate(call, referencedFunction, scopeReceiver)

if context has createQualifier (propagated from inner create()):
  → handleDefinitionWithCreateQualifier(...)
```

**`handleTypeParameterCall`**:
```
1. Extract type argument T
2. Get primary constructor of T
3. Extract qualifier from @Named/@Qualifier on class
4. Collect DslDef for safety validation (if compileSafety enabled)
5. Find target: buildSingle / buildFactory / buildScoped / buildViewModel / buildWorker
   via findTargetFunction() — cached in targetFunctionCache
6. Create lambda via LambdaBuilder.create():
   { scope: Scope, params: ParametersHolder ->
     T(argumentGenerator.generateForParameter(param1, scope, params, builder),
       argumentGenerator.generateForParameter(param2, scope, params, builder),
       ...)
   }
7. Build irCall to target function:
   extensionReceiver = moduleReceiver
   putTypeArgument(0, T)
   putValueArgument(0, T::class)        // KClass reference
   putValueArgument(1, qualifier)        // named("x") or null
   putValueArgument(2, lambda)           // definition lambda
```

**`handleScopeCreate`**:
```
1. Get referenced function/constructor from create(::T)
2. If IrConstructor:
   For each parameter: argumentGenerator.generateForParameter(param, scopeReceiver, null, builder)
   Return: irCall(constructor).apply { putValueArgument(i, arg) for each }
3. If @Named/@Qualifier on function:
   Store qualifier in context for enclosing definition
4. If inside single/factory/scoped:
   Collect DslDef
5. If unsafeDslChecks: validateCreateInLambda()
```

**`.bind()` chaining**:
```
collectBindType(expression):
  if callee is "bind" with KClass parameter:
    Extract bound class from type argument
    Update last DslDef.bindings += bound class
```

#### 6.3 Public Output

```kotlin
val dslDefinitions: List<Definition.DslDef>                    // For Phase 2.5, 3, 3.1
val collectedCallSites: List<PendingCallSiteValidation>        // For Phase 3.5
val moduleIncludes: Map<String, List<String>>                  // For Phase 3.1
val startKoinModules: List<String>                              // For Phase 3.1
```

---

## 7. IR Phase 2.5 — DSL Hint Generation

### `DslHintGenerator` (DslHintGenerator.kt:30)

**Condition**: `compileSafetyEnabled && dslDefinitions.isNotEmpty()`

```
generateDslDefinitionHints(moduleFragment, dslDefinitions):
  for each DslDef:
    hintName = dsl_<type>   (e.g., dsl_single, dsl_factory)

    Create IrSimpleFunction with parameters:
      [0] contributed: TargetType           // Primary type
      [1..n] binding0: Interface1, ...      // Bindings
      [n+1] dsl_module_<id>: Unit           // Module property ID (if any)
      [n+2] providerOnly: Unit              // If providerOnly flag set
      [n+3] qualifier_<name>: Unit          // String qualifier (dots → $)
      OR    qualifierType: QualifierClass   // Type qualifier

    Create synthetic IrFile in org.koin.plugin.hints
    Register with metadataDeclarationRegistrar
```

**Discovery methods** (for downstream consumers):

```
discoverDslDefinitionTypes(): Set<String>
  // context.referenceFunctions(dsl_*) → extract parameter types → FqName strings

discoverDslDefinitionsFromHints(): List<Definition.DslDef>
  // Reconstructs DslDef from hint function parameters (bindings, qualifier, module ID)
```

---

## 8. IR Phase 3 — startKoin Transformation

### `KoinStartTransformer` (KoinStartTransformer.kt)

**Extends `IrElementTransformerVoid`.**

#### 8.1 `visitCall(expression)`

**Non-generic entry points** (set `hasKoinEntryPoint = true`):
```
startKoin { }                           // FqName: org.koin.core.context.startKoin
GlobalContext.startKoin()               // Same
KoinApplication.init()                  // FqName: org.koin.core.KoinApplication.init
```

**Generic stubs** (transformed):
```
startKoin<T>()         → startKoinWith(listOf(Mod1().module(), ...), { lambda })
koinApplication<T>()   → koinApplicationWith(listOf(...), { lambda })
koinConfiguration<T>() → koinConfigurationWith(listOf(...))
withConfiguration<T>() → .withConfigurationWith(listOf(...))
```

**Module loaders**:
```
KoinApplication.module<T>()           → KoinApplication.modules(T().module())
KoinApplication.modules(A::class, B::class) → KoinApplication.modules(A().module(), B().module())
```

#### 8.2 Transformation Flow (for generic stubs)

```
1. Extract type argument T (the @KoinApplication class)
2. appClass = resolve T to IrClass
3. moduleClasses = extractModulesFromKoinApplicationAnnotation(appClass):
     explicit = extractExplicitModules(appClass)
       // @KoinApplication(modules = [M1::class, M2::class])
     labels = extractConfigurationLabels(appClass)
       // @KoinApplication(configurations = ["test"]) → ["test"]
       // default: ["default"]
     discovered = discoverConfigurationModules(labels):
       local = discoverLocalConfigurationModules(labels)
         // Tree walk: @Module + @Configuration with matching labels
       crossModule = discoverModulesFromHints(labels)
         // context.referenceFunctions(configuration_<label>) → parameter type = module class
       combine local + crossModule
     deduplicate by FqName
4. Record IC lookups
5. If safetyValidator:
     safetyValidator.validateFullGraph(appName, allModuleIrClasses, ...)  // A3
6. Find implementation function (startKoinWith, koinApplicationWith, etc.)
7. Build modules list:
     moduleFunctionResolver.buildModuleGetCall(moduleClass, builder)
       → irCall(moduleClass.constructor()) → .module()
       → returns IrExpression for ModuleClass().module()
8. Build irCall to implementation function:
     putValueArgument(0, listOf(mod1.module(), mod2.module(), ...))
     putValueArgument(1, originalLambda)  // user's configuration lambda
```

---

## 9. IR Phase 3.1 — DSL-only A3 Validation

### `CallSiteValidator.validateDslDefinitionGraph()` (CallSiteValidator.kt:344)

**Condition**: `safetyValidator != null && dslDefinitions.isNotEmpty() && assembledGraphTypes.isEmpty() && hasKoinEntryPoint`

```
1. allDefinitions = dslDefinitions + dependency DSL hints + annotation definitions
2. reachableModuleIds = computeReachableModules(startKoinModules, moduleIncludes)
     // BFS from startKoinModules through moduleIncludes graph
3. (reachableDefs, unreachableDefs) = partitionByReachability(allDslDefs, reachableModuleIds)
     // DslDef.modulePropertyId null or in reachableModuleIds → reachable
4. providerDefinitions = reachableDefs + annotation definitions
5. defsToValidate = reachableDefs excluding providerOnly
6. registry.validateModule("DSL graph", providerDefinitions, parameterAnalyzer, qualifierExtractor, defsToValidate)
7. If unreachable defs: reportUnreachableModules(unreachableDefs)
8. Populate assembledGraphTypes for A4
```

---

## 10. IR Phase 3.5 — Call-site Validation

### `CallSiteValidator.validatePendingCallSites()` (CallSiteValidator.kt:41)

**Condition**: `safetyValidator != null && pendingCallSites.isNotEmpty()`

```
hasFullGraph = assembledGraphTypes.isNotEmpty()
dslHintTypes = if (!hasFullGraph) dslHintGenerator.discoverDslDefinitionTypes() else emptySet()

allKnownTypes = assembledGraphTypes + dslHintTypes + local DSL types + annotation types

for each callSite in pendingCallSites:
  if ProvidedTypeRegistry.isProvided(targetFqName): skip
  if BindingRegistry.isWhitelistedType(targetFqName): skip
  if targetFqName in allKnownTypes: OK
  if !hasFullGraph && targetClass has definition annotation: OK (heuristic)
  if !hasFullGraph:
    if isExternalType || no local DSL: defer → add to unresolvedCallSites
    else: ERROR (local type, local DSL, not found)
  else: ERROR (full graph available, not found)

if unresolvedCallSites.isNotEmpty():
  generateCallSiteHints(moduleFragment, unresolvedCallSites)
    // Creates callsite(required: TargetType) hint functions
```

---

## 11. IR Phase 3.6 — Cross-module Call-site Hints

### `CallSiteValidator.validateCallSiteHintsFromDependencies()` (CallSiteValidator.kt:262)

**Condition**: `safetyValidator != null && compileSafetyEnabled && assembledGraphTypes.isNotEmpty()`

```
hintFunctions = context.referenceFunctions(CallableId(HINTS_PACKAGE, "callsite"))
if empty: return

allKnownTypes = assembledGraphTypes + local DSL + dependency DSL hints + annotation definitions

for each hintFunction:
  targetClass = parameter type
  targetFqName = targetClass.fqNameWhenAvailable
  if ProvidedTypeRegistry.isProvided(targetFqName): skip
  if BindingRegistry.isWhitelistedType(targetFqName): skip
  if targetFqName in allKnownTypes: OK
  else: ERROR
```

---

## 12. IR Phase 4 — @Monitor Transformation

### `KoinMonitorTransformer` (KoinMonitorTransformer.kt)

**Extends `IrElementTransformerVoid`.**

```
Wraps @Monitor function bodies with:
  KotzillaCore.getDefaultInstance().trace("functionName") { originalBody }
  // For suspend: suspendTrace("functionName") { originalBody }

@Monitor on class → applies to all public functions in that class.

logSummary(): Reports number of functions transformed + warnings for missing SDK.
```

---

## 13. Helper Classes

### `QualifierExtractor` (QualifierExtractor.kt:67)

**Qualifier extraction priority** (both `extractFromDeclaration` and `extractFromParameter`):

1. `@Named("x")` — Koin, jakarta.inject, javax.inject → `StringQualifier(x)`
2. `@Qualifier(SomeType::class)` — type-based → `TypeQualifier(IrClass)`
3. `@Qualifier(name = "x")` — string-based → `StringQualifier(x)`
4. Custom qualifier (annotation annotated with @Qualifier/@Named):
   - Same-module: check meta-annotations on annotation class
   - Cross-module fallback: check `knownCustomQualifiers` set (from `qualifier()` hints)
   - If annotation has enum value arg: `StringQualifier("EnumClass.ENTRY")`
   - If annotation has string value arg: `StringQualifier(value)`
   - Else: `StringQualifier(annotationClassName)`

**Annotation check methods**:
```
hasInjectedParamAnnotation(param): Boolean  // @InjectedParam
hasProvidedAnnotation(param): Boolean        // @Provided
getPropertyAnnotationKey(param): String?     // @Property("key") → "key"
getScopeIdAnnotationName(param): String?     // @ScopeId(MyScope::class) → FQ name
                                              // @ScopeId(name = "x") → "x"
```

**IR call creation**:
```
createNamedQualifierCall(name, builder)      → named("name")
createTypeQualifierCall(irClass, builder)    → typeQualifier<T>(T::class)
createQualifierCall(qualifier?, builder)     → dispatches to above
```

### `KoinArgumentGenerator` (KoinArgumentGenerator.kt:43)

**Implements `ArgumentGenerator`.** Generates IR expressions for constructor/function parameters.

**Resolution priority** (`generateKoinArgumentForParameter`, L63-145):

```
1. @Property("key")
   → Non-nullable: createGetPropertyCall(scope, key, builder)
     → If PropertyValueRegistry.hasDefault(key): scope.getProperty(key, default)
     → Else: scope.getProperty(key)
   → Nullable: createGetPropertyOrNullCall(scope, key, builder)

2. @ScopeId("name")
   → createGetFromNamedScopeCall(scope, scopeId, type, builder)
     → scope.getScope("scopeId").get<T>()

3. @InjectedParam
   → Non-nullable: createParametersHolderGetCall(params, type, builder)
     → parametersHolder.get<T>()
   → Nullable: createParametersHolderGetOrNullCall(params, type, builder)
     → parametersHolder.getOrNull<T>()

4. @Provided → no special codegen, falls through to normal get()

5. Extract qualifier from parameter: qualifierExtractor.extractFromParameter(param)

6. skipDefaultValues check:
   if skipDefaultValuesEnabled && param.defaultValue != null && qualifier == null && !nullable:
     return null  // Use Kotlin default

7. Classify by type:
   a. Scope type → return scopeReceiver directly
   b. Lazy<T> → createScopeInjectCall(scope, T, Lazy<T>, qualifier, builder)
      → scope.inject<T>(qualifier, LazyThreadSafetyMode.SYNCHRONIZED)
   c. List<T> → createScopeGetAllCall(scope, T, List<T>, builder)
      → scope.getAll<T>()

8. Nullable → createScopeGetOrNullCall(scope, T, qualifier, builder)
   → scope.getOrNull<T>(qualifier)

9. Default → createScopeGetCall(scope, T, qualifier, builder)
   → scope.get<T>(qualifier)
```

**WASM fix**: All `irCall` sites pass explicit return type: `irCall(symbol, concreteType)` to prevent unbound `IrTypeParameterSymbolImpl`.

### `LambdaBuilder` (LambdaBuilder.kt:51)

Creates lambda expressions: `{ scope: Scope, params: ParametersHolder -> ... }`

```
create(returnTypeClass, builder, parentFunction, bodyBuilder):
  lambdaFunction = createSimpleFunction(<anonymous>)
  extensionReceiverParam = Scope <this>      (index = -1)
  parametersHolderParam = params              (index = 0)
  body = bodyBuilder(lambdaBuilder, scopeParam, paramsParam)
  type = Function2<Scope, ParametersHolder, ReturnType>
  return IrFunctionExpressionImpl(LAMBDA, lambdaFunction)
```

### `ScopeBlockBuilder` (ScopeBlockBuilder.kt:54)

Creates scope blocks for annotations:

```
buildScopeBlock(scopeClass, moduleReceiver, parent, builder, statementsBuilder):
  Find scope(qualifier, block) in org.koin.plugin.module.dsl
  Create ScopeDSL.() -> Unit lambda
  Create typeQualifier<ScopeClass>() qualifier
  Return: module.scope(typeQualifier<ScopeClass>()) { statementsBuilder... }

buildArchetypeScopeBlock(archetype, moduleReceiver, parent, builder, statementsBuilder):
  Find archetype function (viewModelScope, activityScope, etc.)
  Create ScopeDSL.() -> Unit lambda
  Return: module.viewModelScope { statementsBuilder... }
```

### `ParameterAnalyzer` (ParameterAnalyzer.kt:27)

Mirrors `KoinArgumentGenerator` logic but produces `Requirement` data instead of IR.

**Classification sequence** (`analyzeParameter`, L48-240):
```
1. @Property("key") → Requirement(isProperty=true, propertyKey=key)
2. @ScopeId("name") → Requirement(isScopeId=true, scopeIdName=name)
3. @InjectedParam → Requirement(isInjectedParam=true)
4. @Provided → Requirement(isProvided=true)
5. Scope type → Requirement(isProvided=true) // scope receiver injection
6. Lazy<T> → Requirement(isLazy=true, typeKey=innerType)
7. List<T> → Requirement(isList=true, typeKey=elementType)
8. Regular → Requirement(typeKey=type, qualifier=qualifier)
```

### `BindingRegistry` (BindingRegistry.kt:72)

**Validation engine.** Contains the actual matching logic.

**Whitelisted types** (L79-89): Android framework types always available at runtime:
```
android.content.Context, android.app.Activity, android.app.Application,
androidx.activity.ComponentActivity, androidx.fragment.app.Fragment,
androidx.lifecycle.SavedStateHandle, androidx.work.WorkerParameters
```

**`validateModule(moduleName, definitions, analyzer, extractor, definitionsToValidate)`** (L107):
```
1. Build providedTypes set from ALL definitions:
   For each definition:
     Add ProviderKey(typeKey, qualifier, scopeClass)
     For each binding: Add ProviderKey(bindingTypeKey, qualifier, scopeClass)

2. For each definition in definitionsToValidate:
   Extract requirements via analyzer
   For each requirement:
     if !requiresValidation(): skip (log reason)
       // Inline @Property/@PropertyValue validation here
     if ProvidedTypeRegistry.isProvided(fqName): skip
     if isWhitelistedType(fqName): skip
     findProvider(req, providedTypes, defScopeClass):
       For each provider:
         Type match? (FqName or ClassId)
         Qualifier match?
         Scope visibility:
           provider.scopeClass == null → visible to all
           provider.scopeClass == consumer.scopeClass → visible
           else → not visible
     if !found: reportMissingDependency(req, defName, moduleName, providedTypes)
       // Error includes "Hint: Found similar binding" for qualifier mismatches
```

### `CompileSafetyValidator` (CompileSafetyValidator.kt:20)

**Orchestrator.** Coordinates A2 and A3 validation.

```
validate(moduleName, moduleFqName, ownDefinitions, allVisibleDefinitions):
  // A2: per-module
  registry.validateModule(moduleName, allVisibleDefinitions, analyzer, extractor, ownDefinitions)
  validatedModuleFqNames.add(moduleFqName)  // Track to skip at A3

validateFullGraph(appName, allModuleIrClasses, collectedModuleClasses, getDefsFn, getDepDefsFn, dslDefs):
  // A3: full-graph
  1. For each module: collect definitions + track if already validated at A2
  2. Add DSL definitions as both providers and consumers
  3. Store assembledGraphTypes (for A4)
  4. If any module incomplete: SKIP
  5. registry.validateModule(appName, allDefinitions, analyzer, extractor, definitionsToValidate)
     // definitionsToValidate excludes A2-validated modules
```

---

## 14. Data Models

### `AnnotationModels.kt`

```kotlin
data class ModuleClass(irClass, hasComponentScan, scanPackages, definitionFunctions, includedModules)
data class DefinitionClass(irClass, definitionType, packageFqName, bindings, scopeClass?, scopeArchetype?, createdAtStart)
data class DefinitionFunction(irFunction, definitionType, returnTypeClass, scopeClass?, scopeArchetype?, createdAtStart)
data class DefinitionTopLevelFunction(irFunction, definitionType, packageFqName, returnTypeClass, bindings, scopeClass?, scopeArchetype?, createdAtStart)

sealed class Definition {
  abstract val definitionType: DefinitionType
  abstract val returnTypeClass: IrClass
  abstract val bindings: List<IrClass>
  abstract val scopeClass: IrClass?
  abstract val scopeArchetype: ScopeArchetype?
  abstract val createdAtStart: Boolean

  data class ClassDef(irClass, ...)           // @Singleton class A
  data class FunctionDef(irFunction, moduleInstance, ...)  // @Module fun provide()
  data class TopLevelFunctionDef(irFunction, ...)          // @Singleton fun provideX()
  data class DslDef(irClass, modulePropertyId?, providerOnly, qualifier?, ...) // single<T>()
  data class ExternalFunctionDef(returnTypeClass, qualifier?, ...)  // Cross-module function hint
}

enum class DefinitionType { SINGLE, FACTORY, SCOPED, VIEW_MODEL, WORKER }

data class DependencyModuleResult(definitions: List<Definition>, isComplete: Boolean)
```

### `BindingRegistry.kt`

```kotlin
data class TypeKey(classId: ClassId?, fqName: FqName?) { fun render(): String }

data class Requirement(
    typeKey, paramName, isNullable, hasDefault,
    isInjectedParam, isProvided, isScopeId, scopeIdName,
    isLazy, isList, isProperty, propertyKey, qualifier
) {
    fun requiresValidation(): Boolean
    // false if: @InjectedParam, @Provided, @ScopeId, nullable, List, @Property,
    //           hasDefault+skipDefaultValues+noQualifier
}

data class ProviderKey(typeKey, qualifier, scopeClass)
```

### `QualifierExtractor.kt`

```kotlin
sealed class QualifierValue {
    data class StringQualifier(name: String)
    data class TypeQualifier(irClass: IrClass)
}
```

### `CallSiteValidator.kt`

```kotlin
data class PendingCallSiteValidation(
    targetFqName: String, callFunctionName: String,
    targetClass: IrClass, filePath: String?, line: Int, column: Int
)
```

---

## 15. Hint Function Naming Convention

| Pattern | FIR/IR | Example | Purpose |
|---------|--------|---------|---------|
| `configuration_<label>` | FIR | `configuration_default` | @Configuration module discovery |
| `definition_<type>` | FIR | `definition_single` | Orphan class definition |
| `definition_function_<type>` | FIR | `definition_function_factory` | Orphan top-level function |
| `moduledef_<moduleId>__<funcName>` | FIR | `moduledef_com_example_DaosModule__providesTopicDao` | Module function per-function ABI |
| `qualifier` | FIR | `qualifier` | Custom qualifier annotation |
| `componentscan_<moduleId>_<type>` | IR | `componentscan_com_example_CoreModule_single` | Module-scoped class definition |
| `componentscanfunc_<moduleId>_<type>` | IR | `componentscanfunc_com_example_CoreModule_factory` | Module-scoped function definition |
| `dsl_<type>` | IR | `dsl_single` | DSL definition hint |
| `callsite` | IR | `callsite` | Deferred call-site validation |

**Parameter encoding** (all hint functions in `org.koin.plugin.hints`):
```
contributed: TargetType              // Primary type (always first)
binding0: Interface1                 // Binding #0 (typed parameter)
binding1: Interface2                 // Binding #1
scope: ScopeType                     // Scope class
qualifier_<name>: Unit               // String qualifier (name in param name)
qualifierType: QualifierClass        // Type qualifier
funcpkg_<pkg>: Unit                  // Function package (if differs from return type)
dsl_module_<id>: Unit               // Module property ID (DSL hints)
providerOnly: Unit                   // Provider-only flag (DSL hints)
required: TargetType                 // Call-site target (callsite hints)
```

---

## 16. Validation Layers

| Layer | Phase | When | Scope | What |
|-------|-------|------|-------|------|
| **A2** | 1 | Per-module, after collectAllDefinitions | Module + includes + @Configuration siblings | Each definition's requirements resolvable from visible providers |
| **A3** | 3 | At startKoin<T>(), after module discovery | All modules in graph + DSL defs | Full-graph validation (skips A2-validated) |
| **A3-DSL** | 3.1 | At startKoin{} (no type param) | Reachable DSL defs + annotation defs | DSL graph with module reachability |
| **A4** | 3.5 | After all transformations | Call sites (get<T>, inject<T>, etc.) | Call-site against graph. Defers to hints if no full graph |
| **A4-deferred** | 3.6 | After A4, if full graph available | Call-site hints from dependencies | Cross-module call-site validation |
| **@Property** | 1 (inline) | During A2 requirement scanning | Per-requirement | Warns if @Property("key") has no matching @PropertyValue("key") |

**Error severity**:
- A2/A3/A4 missing dependency → `KoinPluginLogger.error()` → **compilation fails**
- @Property missing → `KoinPluginLogger.warn()` → **warning only**
- Unreachable modules → `KoinPluginLogger.error()` → **compilation fails**
