# Koin Compiler Plugin — Complete Code Walkthrough

> **Version:** 1.0.0 | **Date:** 2026-05-19
>
> Line-by-line debugging reference for the Koin maintainer.
> Every file, every method, every data flow.

---

## Table of Contents

0. [Pipeline Map](#0-pipeline-map) — one-screen mental model
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
17. [Appendix A — Symbol Index by Topic](#17-symbol-index)

Each phase chapter ends with a "Cheat sheet" subsection listing every function in that
phase's files. The Symbol Index (§17) is the master cross-reference grouped by what each
piece does — start there when looking for "where does X happen".

---

## 0. Pipeline Map

The compiler plugin runs in **two big stages** wired by `KoinPluginComponentRegistrar`:
FIR (declaration generation + IC checker) → IR (everything else, in 8 ordered phases).

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Bootstrap   KoinPluginComponentRegistrar.registerExtensions                 │
│             ├── KoinPluginLogger.init           (global config singleton)   │
│             ├── FirExtensionRegistrarAdapter +  KoinPluginRegistrar         │
│             └── IrGenerationExtension       +   KoinIrExtension             │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
        ┌─────────────────────────┴─────────────────────────┐
        ▼ FIR                                               ▼ IR
┌─────────────────────────────┐         ┌─────────────────────────────────────┐
│ KoinModuleFirGenerator      │         │ Phase 0 — KoinHintTransformer       │
│  • registerPredicates       │         │   Fill FIR-stub hint bodies         │
│  • getTopLevelCallableIds   │         │   + registerFunctionAsMetadataVisible│
│  • generateFunctions        │         ├─────────────────────────────────────┤
│    → module() stubs         │         │ Phase 1 — KoinAnnotationProcessor   │
│    → configuration_<label>  │         │   collectAnnotations  (tree walk)   │
│    → definition_*           │         │   generateModuleExtensions          │
│    → definition_function_*  │         │     • A2 validation per @Module     │
│    → moduledef_*            │         │     • emit componentscan_* hints    │
│    → qualifier              │         │     • fill Module.module() bodies   │
│                             │         ├─────────────────────────────────────┤
│ FirKoinLookupRecorder       │         │ Phase 2 — KoinDSLTransformer        │
│  • @KoinApplication →       │         │   single<T>/factory<T>/… → buildX   │
│    record cross-mod lookups │         │   scope<S>{} / scoped<T>            │
│                             │         │   Scope.create(::T)                 │
│                             │         │   collect: dslDefinitions,          │
│                             │         │   pendingCallSites, moduleIncludes  │
│                             │         ├─────────────────────────────────────┤
│                             │         │ Phase 2.5 — DslHintGenerator        │
│                             │         │   emit dsl_<type> hints             │
│                             │         ├─────────────────────────────────────┤
│                             │         │ Phase 3 — KoinStartTransformer      │
│                             │         │   startKoin<T> / koinApplication<T>│
│                             │         │     → load discovered modules       │
│                             │         │   A3 full-graph validation          │
│                             │         ├─────────────────────────────────────┤
│                             │         │ Phase 3.1 — CallSiteValidator       │
│                             │         │   A3-DSL (entry-point, no <T>)      │
│                             │         ├─────────────────────────────────────┤
│                             │         │ Phase 3.5 — CallSiteValidator       │
│                             │         │   validate get<T>/inject<T>/…       │
│                             │         │   emit callsite hints if deferred   │
│                             │         ├─────────────────────────────────────┤
│                             │         │ Phase 3.6 — CallSiteValidator       │
│                             │         │   validate dep callsite hints       │
│                             │         ├─────────────────────────────────────┤
│                             │         │ Phase 4 — KoinMonitorTransformer    │
│                             │         │   wrap @Monitor bodies w/ trace{}   │
│                             │         └─────────────────────────────────────┘
└─────────────────────────────┘
```

### State that lives across phases

| Name | Kind | Lifetime | Written by | Read by |
|------|------|----------|------------|---------|
| `KoinPluginLogger` | global object | compilation | Bootstrap | All phases |
| `PropertyValueRegistry` | global object | compilation | Phase 1 (`processPropertyValue`) | `KoinArgumentGenerator`, `BindingRegistry` |
| `ProvidedTypeRegistry` | global object | compilation | Phase 1 (`processClass`) | `BindingRegistry`, `CallSiteValidator` |
| `KoinAnnotationProcessor.cachedModuleDefinitions` | instance field | IR phases | Phase 1 pre-pass | Phases 3 / 3.1 / 3.5 / 3.6 |
| `KoinDSLTransformer.dslDefinitions` | instance field | IR phases | Phase 2 | Phases 2.5 / 3 / 3.1 / 3.5 / 3.6 |
| `KoinDSLTransformer.collectedCallSites` | instance field | IR phases | Phase 2 | Phase 3.5 |
| `KoinDSLTransformer.moduleIncludes` | instance field | IR phases | Phase 2 | Phase 3.1 |
| `KoinDSLTransformer.startKoinModules` | instance field | IR phases | Phase 2 | Phase 3.1 |
| `CompileSafetyValidator.assembledGraphTypes` | instance field | IR phases | Phase 1 (A2), Phase 3 (A3) | Phase 3.5, 3.6 |
| `KoinStartTransformer.hasKoinEntryPoint` | instance field | IR phases | Phase 3 | Phase 3.6 |

### How a single user feature flows through

| User wrote… | Picked up by | Transformed/validated by | Output |
|-------------|--------------|--------------------------|--------|
| `@Module class M` | FIR `moduleClassInfos` | Phase 1 `generateModuleExtensions` | `fun M.module(): Module` |
| `@Singleton class A` (in `@ComponentScan` path) | FIR `definitionClassInfos` + IR `processClass` | Phase 1 `fillFunctionBody` via `DefinitionCallBuilder` | `single<A> { A(...) }` inside `M.module()` |
| `single<T> { create(::Impl) }` | Phase 2 `visitCall` → `handleTypeParameterCall` | Phase 2.5 emits `dsl_single` hint | `buildSingle(T::class, null) { T(...) }` |
| `@Configuration("test") class M` | FIR `configurationModules` | Phase 3 `discoverConfigurationModules` | Loaded by `startKoin<App>()` when label matches |
| `startKoin<App>()` | Phase 3 `visitCall` | Phase 3 A3 validation + `startKoinWith(...)` rewrite | `startKoinWith(listOf(M1().module(), …), …)` |
| `koinInject<T>()` (compose) | Phase 2 `collectCallSiteIfResolutionFunction` | Phase 3.5 against graph or emits `callsite` hint | Validation error or deferred hint |
| `@Monitor fun foo()` | Phase 4 `visitSimpleFunction` | `wrapBodyWithTrace` | `KotzillaCore.trace("foo") { ... }` |

### Reading order

- **First time?** Read §1 → §3 → §5 → §6 → §8. That's bootstrap, FIR generation, annotation
  processing, DSL rewrite, startKoin rewrite — the spine.
- **Tracking down a bug?** Look up the symbol in §17 (Symbol Index by Topic), then jump to
  the relevant chapter's cheat sheet, then the file.
- **Adding a new annotation?** §3 (declare in FIR) + §5 (`processClass`) + §15 (hint naming).
- **Adding a new DSL function?** §6 (`KoinDSLTransformer`) + §13 (`DefinitionCallBuilder`).
- **Cross-module discovery weirdness?** §15 (hint naming) + §13 (`IncrementalTracking`,
  `HintTypeErasure`).

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
  L10: +::FirKoinLookupRecorder       // IC dependency tracking (LookupTracker / ExpectActualTracker)
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
COMPONENT_SCAN_FUNCTION_ROSTER_PARAM_PREFIX = "q_"
MODULE_DEFINITION_HINT_PREFIX = "moduledef_"
DSL_DEFINITION_HINT_PREFIX = "dsl_"
QUALIFIER_HINT_NAME = "qualifier"
CALLSITE_HINT_NAME = "callsite"
DSL_MODULE_PARAM_PREFIX = "module_"
DEFAULT_LABEL = "default"
MODULE_FUNCTION_NAME = "module"
```

**Qualifier name encoding** (L120, L139): `sanitizeQualifierName` / `unsanitizeQualifierName` escape
non-identifier chars as `$XX` (lowercase hex of code point). `$` itself is doubled. Used so
arbitrary qualifier strings can be embedded in Kotlin identifiers — e.g., the `q_<sanitized>`
parameter names on `componentscanfunc_*` roster hints.

### `KoinAnnotationFqNames` (KoinAnnotationFqNames.kt:11)

All annotation FqNames. Key groups:

- **Module**: `MODULE`, `COMPONENT_SCAN`, `CONFIGURATION`
- **Definition**: `SINGLETON`, `SINGLE`, `FACTORY`, `SCOPED`, `KOIN_VIEW_MODEL`, `KOIN_WORKER`
- **Scope**: `SCOPE`, `VIEW_MODEL_SCOPE`, `ACTIVITY_SCOPE`, `ACTIVITY_RETAINED_SCOPE`, `FRAGMENT_SCOPE`
- **Parameter**: `NAMED`, `QUALIFIER`, `INJECTED_PARAM`, `PROPERTY`, `PROPERTY_VALUE`, `PROVIDED`, `SCOPE_ID`
- **App**: `KOIN_APPLICATION`
- **Monitor**: `MONITOR`, `KOTZILLA_CORE`
- **JSR-330**: `JAKARTA_SINGLETON`, `JAKARTA_INJECT`, `JAKARTA_NAMED`, `JAKARTA_QUALIFIER`, `JAVAX_*`
- **Core classes**: `KOIN_MODULE`, `SCOPE_CLASS`, `PARAMETERS_HOLDER`, `MODULE_DSL`, `SCOPE_DSL`, `PLUGIN_MODULE_DSL`, `QUALIFIER_PACKAGE`
- **Call-site functions**: `CALL_SITE_RESOLUTION_FUNCTIONS` (19 entries: compose, compose-viewmodel, androidx-compose, androidx-compose-navigation, core, android, android-viewmodel, ktor). `Scope.get`/`inject` are intentionally excluded — used by plugin-generated code and indistinguishable from user calls.

---

## 3. FIR Phase

### `KoinModuleFirGenerator` (KoinModuleFirGenerator.kt:85)

**Extends `FirDeclarationGenerationExtension`.** Generates function declarations with null bodies.

#### 3.1 Discovery (Lazy Properties)

All discovery is lazy — evaluated once on first access.

**`moduleClassInfos`** (L699): `@Module` classes
```
predicateBasedProvider.getSymbolsByPredicate(modulePredicate)
  → filter out expect classes
  → capture containingFileName from source (PSI or synthetic)
  → skip dependency classes (null source)
  → returns List<ModuleClassInfo(classSymbol, containingFileName)>
```

Source type detection (in `getContainingFileName`, L1379):
- `KtPsiSourceElement` → `source.psi.containingFile.name` (JVM, standard)
- `RealSourceElementKind` → `syntheticFileName(classId, "Module")` (KMP: Native/JS/Wasm)
- Other → `null` → skip (dependency from JAR)

**`configurationModules`** (L756): `@Module + @Configuration`
```
predicateBasedProvider.getSymbolsByPredicate(configurationPredicate) → configClassIds
moduleClassInfos.filter { classId in configClassIds || coneType annotation found }
  → extract labels from @Configuration vararg
  → default label: ["default"]
  → returns List<ConfigurationModule(classSymbol, labels, containingFileName)>
```

**`localScanPackages`** (L882): Packages from `@ComponentScan`
```
predicateBasedProvider.getSymbolsByPredicate(componentScanPredicate) → scanClassIds
moduleClassInfos.filter { classId in scanClassIds }
  → extract packages from @ComponentScan args (or default to class package)
  → returns Set<String>
```

**`definitionClassInfos`** (L970): Orphan definition classes
```
For each predicate (singleton, single, factory, scoped, viewModel, worker,
    viewModelScope, activityScope, activityRetainedScope, fragmentScope,
    jakartaSingleton, javaxSingleton, jakartaInject, javaxInject):
  predicateBasedProvider.getSymbolsByPredicate(predicate)
    → filter out expect classes
    → filter out classes covered by localScanPackages (isCoveredByLocalScan)
    → capture containingFileName
    → add to definitions list

Then: collectInjectConstructorClasses(definitions)   (L1246)
  → PSI scan for @Inject on constructor (not detectable by predicates)
  → scan files from known classes, then fallback via anyClassPredicate
```

**`definitionFunctionInfos`** (L1042): Orphan top-level functions
```
Same pattern as definitionClassInfos but for FirNamedFunctionSymbol
  → skip functions inside classes (callableId.classId != null)
  → skip Unit return types
  → skip functions covered by localScanPackages
  → extract metadata: qualifierName, qualifierTypeClassId, scopeClassId, bindingClassIds
```

**`moduleDefinitionFunctionInfos`** (L1117): Functions inside @Module classes
```
Same predicates, but filter by containingClassId in moduleClassIds
  → each function generates its own hint for per-function ABI tracking
  → extract metadata: qualifier, scope, bindings
```

**`qualifierAnnotationInfos`** (L1200): Custom qualifier annotations
```
predicateBasedProvider.getSymbolsByPredicate(qualifierAnnotationPredicate)
  → filter for ANNOTATION_CLASS kind, not expect
```

#### 3.2 Predicate Registration

`registerPredicates()` (L1417): Registers ALL predicates with the FIR system.

#### 3.3 Callable ID Generation

`getTopLevelCallableIds()` (L1448): Returns all CallableIds that will be generated.

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

`generateFunctions(callableId, context)` (L1514): Creates FIR function declarations.

**Configuration hints** (L1518-1549):
```
if callableId in HINTS_PACKAGE && matches "configuration_<label>":
  for each ConfigurationModule with matching label:
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", moduleClassType)
    }.markAsDeprecatedHidden()
```

**Module extension functions** (L1552-1590):
```
if callableId.callableName == "module":
  for each ModuleClassInfo in matching package:
    createTopLevelFunction(Key, callableId, KoinModule) {
      extensionReceiverType(moduleClassType)
    }
```

**Definition hints** (L1594-1638):
```
if callableId in HINTS_PACKAGE && matches "definition_<type>":
  for each DefinitionClassInfo with matching defType:
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", classType)
      // metadata params: binding0..n, scope, qualifier_<name>, qualifierType
    }.markAsDeprecatedHidden()
```

**Function definition hints** (L1640-1687):
```
if callableId in HINTS_PACKAGE && matches "definition_function_<type>":
  for each DefinitionFunctionInfo with matching defType:
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", returnClassType)
      // metadata params: binding0..n, scope, qualifier_<name>, qualifierType, funcpkg_<pkg>
    }.markAsDeprecatedHidden()
```

**Module definition hints** (L1689-1719):
```
if callableId in HINTS_PACKAGE && matches "moduledef_<moduleId>__<funcName>":
  find matching ModuleDefinitionFunctionInfo
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", returnClassType)
      // metadata params
    }.markAsDeprecatedHidden()
```

**Qualifier hints** (L1724-1741):
```
if callableId == "qualifier":
  for each QualifierAnnotationInfo:
    createTopLevelFunction(Key, callableId, Unit) {
      valueParameter("contributed", qualifierAnnotationType)
    }.markAsDeprecatedHidden()
```

> **Note**: `componentscan_*` and `componentscanfunc_*` hints are **no longer FIR-generated**.
> They're created at IR time inside `KoinAnnotationProcessor.generateModuleScanHints` because
> the IR phase has visibility into `@Inject` constructor classes in subpackages that FIR
> predicates can't see (predicates don't index constructor annotations). See §5 and §15.

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

### Cheat sheet — FIR phase

`KoinModuleFirGenerator.kt` (1760 lines)

| Member | Kind | What |
|--------|------|------|
| `Companion.hintFunctionNameForLabel` / `labelFromHintFunctionName` | fn | `configuration_<label>` name ↔ label |
| `Companion.definitionHintFunctionName` / `definitionTypeFromHintFunctionName` | fn | `definition_<type>` name ↔ type |
| `Companion.definitionFunctionHintFunctionName` / `definitionTypeFromFunctionHintName` | fn | `definition_function_<type>` ↔ type |
| `Companion.dslDefinitionHintFunctionName` | fn | `dsl_<type>` name builder |
| `Companion.sanitizeModuleIdForHint` | fn | `ClassId → "com_example_Foo"` |
| `Companion.moduleScanHintFunctionName` + `…InfoFromHintFunctionName` | fn | `componentscan_<moduleId>_<type>` ↔ pair |
| `Companion.moduleScanFunctionHintFunctionName` + `…InfoFromHintFunctionName` | fn | `componentscanfunc_*` ↔ pair |
| `Companion.moduleScanFunctionEntryHintName` | fn | `componentscanfunc_…__q_<qual>` (per-qualifier entry) |
| `Companion.moduleScanFunctionRosterHintName` | fn | `componentscanfunc_…__roster` |
| `Companion.moduleDefinitionHintFunctionName` + `…InfoFromHintName` | fn | `moduledef_<moduleId>__<funcName>` ↔ pair |
| `Companion.syntheticFileName` | fn | KMP-safe synthetic file name |
| `Companion.ALL_DEFINITION_TYPES` | val | `[single, factory, scoped, viewmodel, worker]` |
| `Companion.Key` | object | `GeneratedDeclarationKey` tag |
| `ConfigurationModule` / `ModuleClassInfo` | data class | discovery records (file-private) |
| `DefinitionClassInfo` / `DefinitionFunctionInfo` / `ModuleDefinitionFunctionInfo` | data class | discovery records |
| `QualifierAnnotationInfo` | data class | custom qualifier annotation record |
| `platformInfo` | lazy val | "JVM" / "Native" / etc. for debug logs |
| `moduleClassInfos` | lazy val | `@Module` classes + source file (L699) |
| `moduleClasses` | lazy val | subset of `moduleClassInfos` (L749) |
| `configurationModules` | lazy val | `@Module + @Configuration` (L756) |
| `localScanPackages` | lazy val | `@ComponentScan` packages (L882) |
| `definitionClassInfos` | lazy val | orphan definition classes (L970) |
| `definitionFunctionInfos` | lazy val | orphan top-level functions (L1042) |
| `moduleDefinitionFunctionInfos` | lazy val | functions inside `@Module` (L1117) |
| `qualifierAnnotationInfos` | lazy val | custom qualifier annotations (L1200) |
| `extractQualifierName(…)` / `…TypeClassId(…)` | fn | private annotation readers |
| `extractScopeClassId(…)` / `extractExplicitBindingClassIds(…)` | fn | private annotation readers |
| `detectBindingClassIds` | fn | super-type binding detection |
| `extractStringArgument` / `extractClassIdArgument` / `extractClassIdFromExpression` | fn | FIR annotation arg parsers |
| `buildMetadataParams` | fn | encodes bindings/scope/qualifier as hint params (L621) |
| `markAsDeprecatedHidden` | fn ext | adds `@Deprecated(HIDDEN)` annotation (L674) |
| `extractConfigurationLabels` | fn | `@Configuration` label vararg reader |
| `isCoveredByLocalScan` | fn | package-prefix filter |
| `extractComponentScanPackages` | fn | `@ComponentScan` arg reader |
| `collectInjectConstructorClasses` | fn | PSI scan for `@Inject` constructors (L1246) |
| `scanKtFileForInjectConstructorClasses` | fn | recursive `KtClass` walker (L1312) |
| `getContainingFileName` | fn | source-type → file name dispatcher (L1379) |
| `registerPredicates` | override fn | predicate registration (L1417) |
| `getTopLevelCallableIds` | override fn | CallableId emission (L1448) |
| `generateFunctions` | override fn | FIR function emission (L1514) |
| `hasPackage` | override fn | claims `org.koin.plugin.hints` (L1750) |

`FirKoinLookupRecorder.kt` (137 lines)

| Member | Kind | What |
|--------|------|------|
| `declarationCheckers` | val | wires `KoinApplicationLookupChecker` |
| `KoinApplicationLookupChecker.check` | override fn | records IC deps for `@KoinApplication` |
| `KoinApplicationLookupChecker.extractConfigurationLabels` | fn | reads `configurations=` vararg |

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
  isQualifierHint = parentPackage == HINTS_PACKAGE && functionName == KoinPluginConstants.QUALIFIER_HINT_NAME
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

### Cheat sheet — Phase 0

`KoinHintTransformer.kt` (88 lines)

| Member | Kind | What |
|--------|------|------|
| `hintsPackage` | val | cached `FqName("org.koin.plugin.hints")` |
| `visitSimpleFunction` | override fn | fills FIR-stub bodies + registers them metadata-visible |
| `generateHintFunctionBody` | fn | builds `error("Stub!")` body |

---

## 5. IR Phase 1 — Annotation Processing

### `KoinAnnotationProcessor` (KoinAnnotationProcessor.kt:64)

**NOT a transformer.** Called directly from `KoinIrExtension`. Largest single file in the
plugin (~2500 lines) — covers annotation collection, module-extension generation,
componentscan hint emission at IR time, and dependency-module definition resolution.

Public entry points (heavily used by Phases 3/3.1/3.5/3.6):

| Method | Line | Purpose |
|--------|------|---------|
| `collectAnnotations(moduleFragment)` | L171 | Phase 1 tree walk |
| `generateModuleExtensions(moduleFragment)` | L626 | Phase 1 module-fun generation |
| `getDefinitionsForModule(moduleClass)` | L123 | Cached lookup for validators |
| `getAllKnownDefinitions()` | L157 | All known definitions (local + dependency) |
| `getDefinitionsForDependencyModule(fqName)` | L164 | Resolve definitions from a dependency module class |
| `collectDefinitionsFromDependencyModule(...)` | L1717 | Cross-module definition resolution (used by A3) |

#### 5.1 `collectAnnotations(moduleFragment)` — Single tree walk (L171)

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

#### 5.2 `generateModuleExtensions(moduleFragment)` — Two passes (L626)

**Pre-pass: Pre-collect definitions for every module + emit module-scan hints**

```
moduleDefinitions = moduleClasses.associateWith { collectAllDefinitions(it) }   // L631
cachedModuleDefinitions = moduleDefinitions  // for getDefinitionsForModule reuse

generateModuleScanHints(moduleFragment, moduleDefinitions)   // L830
  // For each @Configuration module with @ComponentScan:
  //   Collect all hint functions into a list (componentscan_*, componentscanfunc_*,
  //                                            and per-qualifier __q_<sanitized> entries + __roster)
  //   Create ONE synthetic IrFile per module
  //   Register each function as metadata-visible
  // This is the IR-time replacement for the FIR componentscan_ hints —
  // see HintTypeErasure.kt for why hint param types are erased to Any?.
```

**Pass 1: Build visibility, validate (A2), create/locate module function**

```
includedModuleFqNames = ∪ { mc.includedModules.map { it.fqNameWhenAvailable } }

for each moduleClass in moduleClasses:
  definitions = moduleDefinitions[moduleClass]

  // A2 — skip modules that are included by another local module
  // (parent's visibility set / A3 will cover them)
  if safetyValidator != null && definitions.isNotEmpty()
       && moduleClass.fqName !in includedModuleFqNames:
    visibilityResult = buildVisibleDefinitions(moduleClass, definitions, moduleDefinitions)
    if visibilityResult.isComplete:
      safetyValidator.validate(moduleName, moduleFqName, definitions, visibilityResult.definitions)
    // else: dependency hints not available — defer to A3

  // Locate or create the module() function
  // Try (in order):
  //   findModuleFunction(moduleFragment, moduleClass.irClass)         // FIR-generated in this fragment
  //   findModuleFunctionViaContext(moduleClass.irClass)               // synthetic file
  //   createModuleFunction(moduleClass, containingFile)               // fallback
  // If function ended up in __GENERATED__CALLABLES__.kt, MOVE it to moduleClass's source file
  // (prevents class-name collisions across compilations).
  moduleFunctions[moduleClass] = <found or created function>
```

**Pass 2: Fill all function bodies** (after all functions exist for cross-module `includes`)

```
for each (moduleClass, function) in moduleFunctions:
  fillFunctionBody(function, moduleClass)   // L1331
```

**`fillFunctionBody(function, moduleClass)`**:
```
Find org.koin.dsl.module() function
Build lambda: Module.() -> Unit
  For each definition (via DefinitionCallBuilder — see §13):
    if definition has scope or scopeArchetype:
      buildScopeBlock / buildArchetypeScopeBlock { scoped { ... } }
    else:
      DefinitionCallBuilder.buildClassDefinitionCall / buildFunctionDefinitionCall /
      buildTopLevelFunctionDefinitionCall:
        extensionReceiver = module
        type arg 0    : T
        value arg 0   : T::class (KClass reference)
        value arg 1   : qualifier (named("x") / typeQualifier<T>() / null)
        value arg 2   : definition lambda (see LambdaBuilder)
        + createdAtStart=true (SINGLE only)
        + .bind chain for each binding
  For includes:
    module.includes(Mod1().module(), Mod2().module())  // built via buildIncludesCall + ModuleFunctionResolver
Build: module { ... lambda ... }
```

### Cheat sheet — Phase 1

`KoinAnnotationProcessor.kt` (2525 lines)

**Caches / state**

| Member | Kind | What |
|--------|------|------|
| `cachedReferenceFunctions` | fn (L109) | memoized `context.referenceFunctions` |
| `currentModuleFragment` | field | active `IrModuleFragment` |
| `cachedModuleDefinitions` | field | `Map<ModuleClass, List<Definition>>` (populated pre-pass) |

**Public API** (used by other phases)

| Member | Line | What |
|--------|------|------|
| `getDefinitionsForModule` | L123 | cached definitions for one module |
| `getAllKnownDefinitions` | L157 | union of all local + dependency definitions |
| `getDefinitionsForDependencyModule` | L164 | resolve a dependency module's definitions by FqName |
| `collectAnnotations` | L171 | tree walk |
| `generateModuleExtensions` | L626 | module-fun generation + A2 |
| `collectDefinitionsFromDependencyModule` | L1717 | cross-module definition extraction (called by A3) |

**Tree walk visitors** (inside `collectAnnotations`)

| Member | Line | What |
|--------|------|------|
| `processPropertyValue` | L208 | `@PropertyValue` → register in `PropertyValueRegistry` |
| `processClass` | L242 | `@Module` / `@Singleton` / `@Provided` etc. |
| `processTopLevelFunction` | L326 | `@Singleton fun provideX(): T` |

**Annotation readers**

| Member | Line | What |
|--------|------|------|
| `getDefinitionAnnotationName` | L348 | which definition annotation (if any) |
| `logDefinitionDiscovery` | L372 | userLogs entry |
| `getScopeClass` | L412 | `@Scope(MyScope::class)` |
| `getDefinitionType` | L424 | annotation → `DefinitionType` |
| `hasInjectConstructor` | L453 | jakarta/javax `@Inject` on ctor |
| `getScopeArchetype` | L468 | `@ViewModelScope`/`@ActivityScope`/etc. |
| `getCreatedAtStart` | L481 | `@Single(createdAtStart = true)` |
| `getExplicitBindings` | L506 | `null` vs `[]` vs `[A, B]` — see §5.1 |
| `IrDeclaration.hasAnnotation` | L541 | local helper |
| `collectDefinitionFunctions` | L550 | functions inside `@Module` class |
| `detectBindings` / `detectAutoBindings` | L568 / L2510 | super-type binding detection |
| `getComponentScanPackages` | L570 | `@ComponentScan` arg reader |
| `getModuleIncludes` | L591 | `@Module(includes = [...])` |

**Module function lifecycle**

| Member | Line | What |
|--------|------|------|
| `createModuleFunction` | L1234 | create new `IrSimpleFunction` for `Module.module()` |
| `findModuleFunction` | L1286 | locate FIR-generated `module()` in the fragment |
| `findExistingModuleFunctionInDependencies` | L1304 | skip if module is in a compiled dep |
| `findModuleFunctionViaContext` | L2491 | fallback via `context.referenceFunctions` |
| `fillFunctionBody` | L1331 | Pass-2 body generator (calls `DefinitionCallBuilder`) |
| `generateErrorStubBody` | L1382 | body when DSL artifact missing |

**Definition discovery**

| Member | Line | What |
|--------|------|------|
| `collectAllDefinitions` | L1399 | local classes + functions + cross-mod hints |
| `findMatchingDefinitions` | L1471 | local `@Singleton` classes by scan packages |
| `findMatchingTopLevelFunctions` | L1509 | local `@Singleton` top-level fns by scan packages |
| `discoverDefinitionsFromHints` | L1536 | reads `componentscan_*` from deps |
| `discoverFunctionDefinitionsFromHints` | L1632 | reads `componentscanfunc_*` from deps |
| `discoverModuleFunctionBindingsFromHint` | L1846 | reads bindings from `moduledef_*` hints |
| `discoverClassDefinitionsFromHints` | L1884 | reads `definition_*` orphan hints |
| `discoverModuleScanDefinitions` | L1907 | resolves all hints for one module class |
| `discoverConfigurationModulesFromHints` | L2213 | cross-mod `@Configuration` lookup |

**Hint emission (IR-time)**

| Member | Line | What |
|--------|------|------|
| `generateModuleScanHints` | L830 | emits per-`@Configuration`-module hints |
| `createHintFunction` | L983 | builds one hint `IrSimpleFunction` |
| `qualifierDiscriminator` | L1124 | qualifier → sanitized identifier |
| `createRosterHintFunction` | L1136 | emits `__roster` enumerator |
| `extractFirModuleData` | L1182 | metadata for synthetic file creation |
| `buildHintFileName` | L1195 | stable synthetic file name |
| `hasConfigurationAnnotation` | L1211 | local helper |
| `definitionTypeToString` | L1220 | `DefinitionType` → name |

**Module body building** (called from `fillFunctionBody`)

| Member | Line | What |
|--------|------|------|
| `buildModuleCall` | L2242 | top-level `module { … }` wrapper |
| `buildScopeBlock` | L2376 | `module.scope(typeQualifier<S>()) { … }` |
| `buildArchetypeScopeBlock` | L2400 | `module.viewModelScope { … }` etc. |
| `buildScopedDefinitions` | L2423 | scoped-block body |
| `buildIncludesCall` | L2450 | `module.includes(...)` |

**Misc helpers**

| Member | Line | What |
|--------|------|------|
| `definitionDedupeKey` | L2032 | dedupe across hint sources |
| `addExternalFunctionDefFromHint` | L2055 | build `ExternalFunctionDef` from hint params |
| `parseDefinitionType` | L2099 | string → `DefinitionType` |
| `matchesScanPackages` | L2111 | package prefix match |
| `ModuleClass.effectiveScanPackages` | L2118 ext | scan packages or class package |
| `getModulesByFqName` | L2142 | FqName → `ModuleClass` index |
| `buildVisibleDefinitions` | L2148 | A2 visibility set (own + includes + Configuration siblings) |

---

## 6. IR Phase 2 — DSL Transformation

### `KoinDSLTransformer` (KoinDSLTransformer.kt:36)

**Extends `IrElementTransformerVoid`.** Transforms DSL calls in user code.

#### 6.1 Context Stack

```kotlin
data class TransformContext(
    val function: IrFunction? = null,          // Current function
    val lambda: IrSimpleFunction? = null,      // Current lambda
    val definitionCall: Name? = null,          // "single", "factory", etc.
    val definitionCallTypeArg: IrClass? = null, // T from single<T> { } — what runtime Koin registers
    val scopeTypeClass: IrClass? = null,       // scope<ScopeType> { }
    val createQualifier: QualifierValue? = null, // Propagated from create(::ref)
    val createReturnClass: IrClass? = null,    // Return class from create()
    val modulePropertyId: String? = null       // Module property ID for DSL tracing
)
```

`withContext(newContext, block)` (L170) — save/restore pattern. After block, checks if inner
context set `createQualifier` and propagates upward.

`definitionCallTypeArg` is load-bearing: when `single<T> { create(::Impl) }` is used,
the registered type must be `T` (not `Impl`) so `get<T>()` resolves at runtime. The
inner `create(::Impl)` is only a construction detail.

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

### Cheat sheet — Phase 2

`KoinDSLTransformer.kt` (803 lines)

**Caches / config**

| Member | Kind | What |
|--------|------|------|
| `unsafeDslChecksEnabled` / `compileSafetyEnabled` | val | snapshot of `KoinPluginLogger` flags |
| `currentFile` | field | tracked by `visitFile` for error locations |
| `qualifierExtractor` / `argumentGenerator` / `lambdaBuilder` | val | helper instances |
| `targetFunctionNames` | val | `single` → `buildSingle`, … map |
| `definitionNames` | val | `{single, factory, scoped, viewModel, worker}` |
| `kClassClass` / `kClassConstructorClass` | lazy val | cached IR references |
| `targetFunctionCache` | mutableMap | `(Name, receiver) → IrSimpleFunction?` cache |
| `transformContext` | field | stack-managed `TransformContext` (see §6.1) |

**Collected outputs**

| Member | Kind | What |
|--------|------|------|
| `dslDefinitions` | List getter | DSL defs found in user code |
| `collectedCallSites` | List getter | `get<T>`/`inject<T>` call sites |
| `moduleIncludes` | Map getter | `module → includes` graph |
| `startKoinModules` | List getter | modules loaded via `KoinApplication.modules` |

**Visitor overrides**

| Member | Line | What |
|--------|------|------|
| `visitFile` | L60 | track current file |
| `visitFunctionExpression` | L125 | push lambda context |
| `visitFunction` | L131 | push function context |
| `visitProperty` | L137 | track `Module` properties for DSL tracing |
| `visitCall` | L196 | main DSL rewriter (see §6.2) |

**Helpers**

| Member | Line | What |
|--------|------|------|
| `buildModulePropertyId` | L153 | property → `"pkg.PropertyName"` |
| `withContext` | L170 | save/restore context with qualifier propagation |
| `collectCallSitesFromExpression` | L235 | recursive expression walker |
| `collectCallSiteIfResolutionFunction` | L353 | enqueue `get<T>` / `inject<T>` call |
| `collectBindType` | L388 | `.bind(I::class)` chain → update last `DslDef` |
| `collectModuleLoadingInfo` | L415 | record `includes` / `modules(...)` info |
| `resolveModuleReferences` | L443 | resolve module ref expressions |
| `resolveModuleRef` | L452 | helper for the above |
| `handleTypeParameterCall` | L481 | `single<T>` → `buildSingle(T::class, ...)` |
| `handleScopeCreate` | L574 | `Scope.create(::T)` → constructor call |
| `handleDefinitionWithCreateQualifier` | L671 | propagated qualifier handling |
| `validateCreateInLambda` | L721 | `unsafeDslChecks` validation |
| `isCreateCall` | L757 | tiny predicate |
| `findTargetFunction` | L761 | resolve `buildSingle`/`buildFactory`/… (cached) |

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

### Cheat sheet — Phase 2.5

`DslHintGenerator.kt` (402 lines)

| Member | Line | What |
|--------|------|------|
| `generateDslDefinitionHints` | L39 | emit `dsl_<type>` hint per `DslDef` |
| `extractFirModuleData` | L267 | FIR metadata for synthetic file |
| `extractFirModuleDataFromModule` | L276 | fallback via `moduleFragment` |
| `buildDslHintFileName` | L287 | stable file name `dsl_<id>.kt` |
| `discoverDslDefinitionTypes` | L305 | dep-side reader → `Set<FqName>` |
| `discoverDslDefinitionsFromHints` | L333 | dep-side reader → `List<DslDef>` |

---

## 8. IR Phase 3 — startKoin Transformation

### `KoinStartTransformer` (KoinStartTransformer.kt:63)

**Extends `IrElementTransformerVoid`.**

Key methods:

| Method | Line |
|--------|------|
| `extractModulesFromKoinApplicationAnnotation` | L275 |
| `extractConfigurationLabels` | L309 |
| `discoverConfigurationModules` | L350 |
| `extractExplicitModules` | L359 |
| `discoverLocalConfigurationModules` | L389 |
| `discoverModulesFromHints` | L416 |
| `transformModuleLoad` | L472 |
| `transformModulesLoad` | L518 |

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

### Cheat sheet — Phase 3

`KoinStartTransformer.kt` (573 lines)

| Member | Line | What |
|--------|------|------|
| `hasKoinEntryPoint` | field | set when non-generic `startKoin{}` / `KoinApplication.init()` seen |
| `visitCall` | (override) | main dispatcher |
| `extractModulesFromKoinApplicationAnnotation` | L275 | union of explicit + discovered modules |
| `extractConfigurationLabels` | L309 | `@KoinApplication(configurations=[...])` |
| `discoverConfigurationModules` | L350 | combined local + cross-mod |
| `extractExplicitModules` | L359 | `@KoinApplication(modules=[...])` |
| `discoverLocalConfigurationModules` | L389 | tree walk for `@Configuration` |
| `discoverModulesFromHints` | L416 | `configuration_<label>` cross-mod hints |
| `hasConfigurationWithMatchingLabels` | L447 | predicate |
| `extractClassFromKClassExpression` | L459 | `T::class` → `IrClass` |
| `transformModuleLoad` | L472 | `KoinApplication.module<T>()` rewrite |
| `transformModulesLoad` | L518 | `KoinApplication.modules(A::class, ...)` rewrite |

---

## 9. IR Phase 3.1 — DSL-only A3 Validation

### `CallSiteValidator.validateDslDefinitionGraph()` (CallSiteValidator.kt:354)

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

### `CallSiteValidator.validateCallSiteHintsFromDependencies()` (CallSiteValidator.kt:272)

**Condition**: `safetyValidator != null && compileSafetyEnabled && startKoinTransformer.hasKoinEntryPoint`

> The old `assembledGraphTypes.isNotEmpty()` gate was removed. Entry-point modules now validate
> call-site hints even with an empty local graph — otherwise a missing Gradle dependency
> (e.g., forgetting `:data` from `:app`) goes undetected because the app sees no definitions
> locally and would skip the check. Without an entry point, library modules don't run this
> phase (would false-positive against the partial local graph).

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

### Cheat sheet — Phases 3.1 / 3.5 / 3.6

`CallSiteValidator.kt` (467 lines)

| Member | Line | What |
|--------|------|------|
| `validatePendingCallSites` | L41 | A4 main loop (Phase 3.5) |
| `generateCallSiteHints` | L148 | emit `callsite(required: T)` hints when graph incomplete |
| `validateCallSiteHintsFromDependencies` | L272 | Phase 3.6 dep-hint validation |
| `validateDslDefinitionGraph` | L354 | Phase 3.1 DSL-only A3 |
| `computeReachableModules` | L416 | BFS over `moduleIncludes` |
| `partitionByReachability` | L435 | split DSL defs into reachable / orphan |
| `reportUnreachableModules` | L453 | emit warning for orphaned `module { … }` properties |

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

### Cheat sheet — Phase 4

`KoinMonitorTransformer.kt` (323 lines)

| Member | Line | What |
|--------|------|------|
| `visitSimpleFunction` | L116 | wrap eligible function bodies |
| `logSummary` | L170 | end-of-phase report |
| `shouldMonitorFunction` | L176 | `@Monitor` on fn or enclosing class? + public/non-suspend check |
| `IrDeclaration.hasAnnotation` | L192 | local helper |
| `generateLabel` | L198 | trace label = function name |
| `wrapBodyWithTrace` | L207 | builds `KotzillaCore.trace("…") { original }` |
| `createTraceLambda` | L250 | lambda containing the original body |
| `transformBodyForLambda` | L299 | rewires `return` statements inside lambda |

---

## 13. Helper Classes

### `QualifierExtractor` (QualifierExtractor.kt:69)

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
validate(moduleName, moduleFqName, ownDefinitions, allVisibleDefinitions):       // L46
  // A2: per-module
  registry.validateModule(moduleName, allVisibleDefinitions, analyzer, extractor, ownDefinitions)
  validatedModuleFqNames.add(moduleFqName)  // Track to skip at A3

validateFullGraph(appName, allModuleIrClasses, collectedModuleClasses, getDefsFn, getDepDefsFn, dslDefs):  // L92
  // A3: full-graph
  1. For each module: collect definitions + track if already validated at A2
  2. Add DSL definitions as both providers and consumers
  3. Store assembledGraphTypes (for A4)
  4. If any module incomplete: SKIP
  5. registry.validateModule(appName, allDefinitions, analyzer, extractor, definitionsToValidate)
     // definitionsToValidate excludes A2-validated modules
```

### `DefinitionCallBuilder` (DefinitionCallBuilder.kt:34)

The largest helper (~650 lines). Builds the IR call expressions that replace `single<T>()`-style
DSL stubs and `@Singleton`-annotated declarations.

| Method | Purpose |
|--------|---------|
| `buildClassDefinitionCall` (L56) | Root-scope class definition: `buildSingle(T::class, qualifier) { ... }` |
| `buildFunctionDefinitionCall` (L159) | Root-scope function definition (inside @Module class) |
| `buildTopLevelFunctionDefinitionCall` (L226) | Root-scope top-level function definition |
| `buildScopedClassDefinitionCall` (L301) | Inside `scope<S> { }` — class definition |
| `buildScopedFunctionDefinitionCall` (L369) | Inside `scope<S> { }` — function definition |
| `buildScopedTopLevelFunctionDefinitionCall` (L431) | Inside `scope<S> { }` — top-level function |
| `addBindings` (L491) | Chains `.bind(I1::class).bind(I2::class)` onto a definition expression |
| `findDefinitionWithKClass` (L553) | Looks up `buildSingle` / `buildFactory` / ... in `org.koin.plugin.module.dsl` |
| `reportMissingDslArtifact` (L540) | Once-per-DefinitionType compile error when DSL artifact is missing from classpath |

**Worker quirk** (L91-96): For `@KoinWorker`, the qualifier is forced to the class FQN string —
required by `WorkManager.getWorkerFactory()` which looks up workers by class name.

### `ModuleFunctionResolver` (ModuleFunctionResolver.kt:30)

Multi-strategy resolver for `module()` extension functions across compilation boundaries.
Used by both `KoinStartTransformer` (to build `ModuleClass().module()` calls) and
`KoinAnnotationProcessor` (to wire `includes`).

Lookup chain (in `buildModuleGetCall`, L38):
1. `findModuleFunction` (L74) — current module fragment, same compilation
2. `findModuleFunctionViaContext` (L94) — `context.referenceFunctions`, candidate filtering by extension receiver
3. `findModuleFunctionViaFileFacade` (L264) — disambiguates via Kotlin file facade class
4. `findModuleFunctionViaClassName` (L307) — JAR class-name convention fallback

The candidate selector (`selectCorrectCandidate`, L144) discards FIR-stub candidates with
mismatched receiver fqName.

### `ConfigurationUtils` (ConfigurationUtils.kt)

Top-level helpers, no class. Shared by `KoinAnnotationProcessor` and `KoinStartTransformer`.

- `hasConfigurationAnnotation(irClass)` (L25)
- `extractConfigurationLabels(irClass)` (L41) — returns `["default"]` when annotation has no args, `[]` when annotation is absent.

### `HintTypeErasure` (HintTypeErasure.kt)

`IrClass.hintParameterType(context)` (L26) — Returns `defaultType` for non-generic classes,
or `typeWith(Any?, Any?, ...)` for generic classes. Workaround for issue #18: synthetic hint
functions can't carry free type parameters (`class Foo<T>`'s `T` has no scope at the hint
declaration site), which crashes the K/Native klib signature mangler. Runtime Koin resolves
on the erased class anyway, so validation fidelity is preserved.

### `IncrementalTracking` (IncrementalTracking.kt)

Adapted from Zac Sweers' Metro plugin. Internal top-level helpers for IC:

- `trackClassLookup(lookupTracker, callingFile, calleeClass)` (L37) — record file → class dependency
- `trackLookup(lookupTracker, callingFile, container, name)` (L53) — record file → declaration dependency
- `linkDeclarationsForIC(expectActualTracker, callingFile, calleeClass)` (L77) — structural-change link via `ExpectActualTracker.report`
- `withLookupTracker(...)` (L97) — null-safe + synchronized wrapper

`ExpectActualTracker` is used as a cross-file structural-change signal (not for KMP expect/actual) —
catches the case where a new declaration is added to a file that didn't previously contain it
(`LookupTracker` can't see it because it didn't exist before).

### `DeprecatedHiddenAnnotation` (DeprecatedHiddenAnnotation.kt)

`IrSimpleFunction.addDeprecatedHiddenAnnotation(context)` (L29) — adds
`@Deprecated("Koin compiler plugin internal hint function", level = DeprecationLevel.HIDDEN)`
to an IR function. Mirrors the FIR-phase `markAsDeprecatedHidden` on the IR side.

**Purpose**: Prevents synthetic hint functions from being exported to ObjC headers on
Kotlin/Native (would otherwise crash with `"An operation is not implemented"` in
`findSourceFile`).

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

  data class ClassDef(irClass, qualifier?, ...)            // @Singleton class A — qualifier propagated from cross-module hint
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
| `componentscanfunc_<moduleId>_<type>` | IR | `componentscanfunc_com_example_CoreModule_factory` | Module-scoped function definition (no-qualifier baseline) |
| `componentscanfunc_<moduleId>_<type>__q_<sanitized>` | IR | `componentscanfunc_com_example_AppModule_single__q_initFlagsAndLogging` | Per-qualifier entry — one hint per qualified top-level function, so collisions don't drop entries when target types coincide (e.g. two `Unit`-returning `@Named @Singleton init` functions) |
| `componentscanfunc_<moduleId>_<type>__roster` | IR | `componentscanfunc_com_example_AppModule_single__roster` | Roster — parameter names (`q_<sanitized>`) enumerate qualifier entries for the consumer to iterate |
| `dsl_<type>` | IR | `dsl_single` | DSL definition hint |
| `callsite` | IR | `callsite` | Deferred call-site validation |

> **Sanitization**: `<sanitized>` is the qualifier string passed through
> `KoinPluginConstants.sanitizeQualifierName` (`$XX` hex-escapes non-identifier chars).

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
q_<sanitized>: Unit                  // Roster entry (one per qualifier on roster hints)
```

> **Hint param-type erasure**: All `contributed`/`binding*` types are run through
> `IrClass.hintParameterType` (see §13 `HintTypeErasure`) — generic classes become
> `Foo<Any?, Any?>` to keep the K/Native klib signature mangler happy.

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

---

## 17. Appendix A — Symbol Index by Topic

A grouped lookup for every notable function/property/constant. Sorted by what each piece
*does*, not where it lives. If you remember the file, use the per-chapter cheat sheets
instead.

### 17.1 Entry points & orchestration

| Symbol | File | Role |
|--------|------|------|
| `KoinPluginComponentRegistrar.registerExtensions` | `KoinPluginComponentRegistrar.kt` | SPI entry, wires both FIR + IR |
| `KoinPluginLogger.init` | `KoinPluginComponentRegistrar.kt:62` | Sets global flags + trackers |
| `KoinPluginRegistrar.configurePlugin` | `KoinPluginRegistrar.kt:8` | Registers FIR extensions |
| `KoinIrExtension.generate` | `ir/KoinIrExtension.kt:14` | **The IR phase orchestrator** — sequences Phases 0→4 |

### 17.2 Discovery (FIR)

| Symbol | File | Role |
|--------|------|------|
| `moduleClassInfos` | `fir/KoinModuleFirGenerator.kt:699` | All `@Module` classes |
| `configurationModules` | `fir/KoinModuleFirGenerator.kt:756` | `@Configuration` modules + labels |
| `localScanPackages` | `fir/KoinModuleFirGenerator.kt:882` | Packages from local `@ComponentScan` |
| `definitionClassInfos` | `fir/KoinModuleFirGenerator.kt:970` | Orphan `@Singleton`/`@Factory`/… classes |
| `definitionFunctionInfos` | `fir/KoinModuleFirGenerator.kt:1042` | Orphan top-level function defs |
| `moduleDefinitionFunctionInfos` | `fir/KoinModuleFirGenerator.kt:1117` | Functions inside `@Module` classes |
| `qualifierAnnotationInfos` | `fir/KoinModuleFirGenerator.kt:1200` | Custom qualifier annotation classes |
| `collectInjectConstructorClasses` | `fir/KoinModuleFirGenerator.kt:1246` | PSI scan for `@Inject` on constructors |

### 17.3 Hint name formation / parsing

| Symbol | File | Direction |
|--------|------|-----------|
| `hintFunctionNameForLabel` ↔ `labelFromHintFunctionName` | `fir/KoinModuleFirGenerator.kt:132/139` | `configuration_<label>` |
| `definitionHintFunctionName` ↔ `definitionTypeFromHintFunctionName` | `:151/158` | `definition_<type>` |
| `definitionFunctionHintFunctionName` ↔ `definitionTypeFromFunctionHintName` | `:172/179` | `definition_function_<type>` |
| `moduleScanHintFunctionName` ↔ `moduleScanInfoFromHintFunctionName` | `:224/235` | `componentscan_<moduleId>_<type>` |
| `moduleScanFunctionHintFunctionName` ↔ `moduleScanFunctionInfoFromHintFunctionName` | `:252/260` | `componentscanfunc_<moduleId>_<type>` |
| `moduleScanFunctionEntryHintName` | `:279` | `componentscanfunc_…__q_<sanitized>` |
| `moduleScanFunctionRosterHintName` | `:290` | `componentscanfunc_…__roster` |
| `moduleDefinitionHintFunctionName` ↔ `moduleDefinitionInfoFromHintName` | `:299/308` | `moduledef_<moduleId>__<funcName>` |
| `dslDefinitionHintFunctionName` | `:194` | `dsl_<type>` |
| `sanitizeQualifierName` ↔ `unsanitizeQualifierName` | `KoinPluginConstants.kt:120/139` | `$XX` hex escape for identifier-safe qualifier strings |
| `sanitizeModuleIdForHint` | `fir/KoinModuleFirGenerator.kt:211` | `ClassId → "com_example_Foo"` |
| `syntheticFileName` | `fir/KoinModuleFirGenerator.kt:335` | KMP-stable synthetic file name |
| `IrClass.hintParameterType` | `ir/HintTypeErasure.kt:26` | Erases generic types for K/Native klib safety |

### 17.4 Hint generation (IR)

| Symbol | File | Role |
|--------|------|------|
| `generateModuleScanHints` | `ir/KoinAnnotationProcessor.kt:830` | Emit `componentscan_*` / `componentscanfunc_*` |
| `createHintFunction` | `ir/KoinAnnotationProcessor.kt:983` | Build one hint `IrSimpleFunction` |
| `createRosterHintFunction` | `ir/KoinAnnotationProcessor.kt:1136` | Build `__roster` enumerator |
| `DslHintGenerator.generateDslDefinitionHints` | `ir/DslHintGenerator.kt:39` | Emit `dsl_<type>` |
| `CallSiteValidator.generateCallSiteHints` | `ir/CallSiteValidator.kt:148` | Emit `callsite` when graph incomplete |
| `KoinHintTransformer.visitSimpleFunction` | `ir/KoinHintTransformer.kt:39` | Fill FIR-stub bodies + `registerFunctionAsMetadataVisible` |
| `addDeprecatedHiddenAnnotation` | `ir/DeprecatedHiddenAnnotation.kt:29` | `@Deprecated(HIDDEN)` to keep hints out of ObjC headers |

### 17.5 Hint consumption (cross-module reads)

| Symbol | File | Reads |
|--------|------|-------|
| `discoverDefinitionsFromHints` | `ir/KoinAnnotationProcessor.kt:1536` | `componentscan_*` from deps |
| `discoverFunctionDefinitionsFromHints` | `ir/KoinAnnotationProcessor.kt:1632` | `componentscanfunc_*` |
| `discoverClassDefinitionsFromHints` | `ir/KoinAnnotationProcessor.kt:1884` | `definition_*` orphan hints |
| `discoverModuleScanDefinitions` | `ir/KoinAnnotationProcessor.kt:1907` | All hints for one module class |
| `discoverConfigurationModulesFromHints` | `ir/KoinAnnotationProcessor.kt:2213` | `configuration_<label>` |
| `KoinStartTransformer.discoverModulesFromHints` | `ir/KoinStartTransformer.kt:416` | Same, for `startKoin<T>()` |
| `DslHintGenerator.discoverDslDefinitionTypes` | `ir/DslHintGenerator.kt:305` | `dsl_<type>` types |
| `DslHintGenerator.discoverDslDefinitionsFromHints` | `ir/DslHintGenerator.kt:333` | Full `DslDef` reconstruction |
| `QualifierExtractor.discoverQualifierHints` | `ir/QualifierExtractor.kt:246` | `qualifier` hints for cross-mod custom qualifiers |

### 17.6 Annotation reading

| Symbol | File | Role |
|--------|------|------|
| `processClass` | `ir/KoinAnnotationProcessor.kt:242` | `@Module` / `@Singleton` / `@Provided` etc. |
| `processTopLevelFunction` | `ir/KoinAnnotationProcessor.kt:326` | Top-level `@Singleton` etc. |
| `processPropertyValue` | `ir/KoinAnnotationProcessor.kt:208` | `@PropertyValue` |
| `getDefinitionType` | `ir/KoinAnnotationProcessor.kt:424` | Annotation → `DefinitionType` |
| `getExplicitBindings` | `ir/KoinAnnotationProcessor.kt:506` | `binds=` reader; **null vs [] is meaningful** |
| `getScopeArchetype` | `ir/KoinAnnotationProcessor.kt:468` | `@ViewModelScope`/etc. |
| `getCreatedAtStart` | `ir/KoinAnnotationProcessor.kt:481` | `@Single(createdAtStart=true)` |
| `getComponentScanPackages` | `ir/KoinAnnotationProcessor.kt:570` | `@ComponentScan` args |
| `hasConfigurationAnnotation` (top-level) | `ir/ConfigurationUtils.kt:25` | `@Configuration` predicate |
| `extractConfigurationLabels` (top-level) | `ir/ConfigurationUtils.kt:41` | `@Configuration("test","prod")` |
| `KoinStartTransformer.extractConfigurationLabels` | `ir/KoinStartTransformer.kt:309` | `@KoinApplication(configurations=…)` |
| `extractExplicitModules` | `ir/KoinStartTransformer.kt:359` | `@KoinApplication(modules=[…])` |

### 17.7 Qualifier extraction

| Symbol | File | Role |
|--------|------|------|
| `QualifierExtractor.extractFromDeclaration` | `ir/QualifierExtractor.kt:94` | Class/function/property |
| `QualifierExtractor.extractFromClass` | `ir/QualifierExtractor.kt:152` | Class-only shortcut |
| `QualifierExtractor.extractFromParameter` | `ir/QualifierExtractor.kt:163` | Constructor/function param |
| `QualifierExtractor.isQualifierMetaAnnotation` | `ir/QualifierExtractor.kt:219` | Recognize `@Qualifier`/`@Named` on annotation class |
| `QualifierExtractor.findCustomQualifierAnnotation` | `ir/QualifierExtractor.kt:283` | Same-module + cross-module custom qualifier lookup |
| `QualifierExtractor.createNamedQualifierCall` | `ir/QualifierExtractor.kt:356` | IR call: `named("x")` |
| `QualifierExtractor.createTypeQualifierCall` | `ir/QualifierExtractor.kt:370` | IR call: `typeQualifier<T>()` |
| `QualifierExtractor.createQualifierCall` | `ir/QualifierExtractor.kt:397` | Dispatcher |

### 17.8 Module function lifecycle (`Module.module()`)

| Symbol | File | Role |
|--------|------|------|
| `createModuleFunction` | `ir/KoinAnnotationProcessor.kt:1234` | Create new `IrSimpleFunction` |
| `findModuleFunction` (processor) | `ir/KoinAnnotationProcessor.kt:1286` | Same-fragment lookup |
| `findExistingModuleFunctionInDependencies` | `ir/KoinAnnotationProcessor.kt:1304` | Skip if already in dep |
| `findModuleFunctionViaContext` (processor) | `ir/KoinAnnotationProcessor.kt:2491` | Fallback via `context.referenceFunctions` |
| `fillFunctionBody` | `ir/KoinAnnotationProcessor.kt:1331` | Pass-2 body fill |
| `ModuleFunctionResolver.buildModuleGetCall` | `ir/ModuleFunctionResolver.kt:38` | `ModuleClass().module()` builder |
| `ModuleFunctionResolver.findModuleFunction…` (chain) | `ir/ModuleFunctionResolver.kt:74/94/264/307` | 4-strategy lookup chain |

### 17.9 DSL transformation (Phase 2)

| Symbol | File | Role |
|--------|------|------|
| `visitCall` | `ir/KoinDSLTransformer.kt` | Dispatcher |
| `handleTypeParameterCall` | `ir/KoinDSLTransformer.kt:481` | `single<T>` → `buildSingle(…)` |
| `handleScopeCreate` | `ir/KoinDSLTransformer.kt:574` | `Scope.create(::T)` |
| `handleDefinitionWithCreateQualifier` | `ir/KoinDSLTransformer.kt:671` | Propagated qualifier |
| `validateCreateInLambda` | `ir/KoinDSLTransformer.kt:721` | `unsafeDslChecks` |
| `collectBindType` | `ir/KoinDSLTransformer.kt:388` | `.bind(I::class)` chain |
| `collectModuleLoadingInfo` | `ir/KoinDSLTransformer.kt:415` | `includes` / `modules(…)` tracking |
| `collectCallSiteIfResolutionFunction` | `ir/KoinDSLTransformer.kt:353` | Queue `get<T>`/`inject<T>` for Phase 3.5 |
| `findTargetFunction` | `ir/KoinDSLTransformer.kt:761` | Look up `buildSingle`/`buildFactory`/… (cached) |

### 17.10 Definition call building (used by Phases 1 + 6)

| Symbol | File | Builds |
|--------|------|--------|
| `DefinitionCallBuilder.buildClassDefinitionCall` | `ir/DefinitionCallBuilder.kt:56` | Root-scope class def |
| `DefinitionCallBuilder.buildFunctionDefinitionCall` | `:159` | `@Module fun provide()` |
| `DefinitionCallBuilder.buildTopLevelFunctionDefinitionCall` | `:226` | Top-level `@Singleton fun` |
| `DefinitionCallBuilder.buildScopedClassDefinitionCall` | `:301` | Inside `scope<S> { }` |
| `DefinitionCallBuilder.buildScopedFunctionDefinitionCall` | `:369` | Same, function form |
| `DefinitionCallBuilder.buildScopedTopLevelFunctionDefinitionCall` | `:431` | Same, top-level form |
| `DefinitionCallBuilder.addBindings` | `:491` | `.bind(I::class)` chain |
| `DefinitionCallBuilder.findDefinitionWithKClass` | `:553` | `buildSingle`/`buildFactory`/… in `plugin.module.dsl` |
| `LambdaBuilder.create` | `ir/LambdaBuilder.kt:84` | `{ scope, params -> … }` lambda |
| `LambdaBuilder.generateArgumentForParameter` / `generateForParameter` | `:195/228` | Per-param IR arg |
| `ScopeBlockBuilder.buildScopeBlock` | `ir/ScopeBlockBuilder.kt:85` | `module.scope(typeQualifier<S>()) { … }` |
| `ScopeBlockBuilder.buildArchetypeScopeBlock` | `:130` | `module.viewModelScope { … }` etc. |
| `ScopeBlockBuilder.createTypeQualifier` | `:158` | `typeQualifier<S>()` call |
| `ScopeBlockBuilder.findScopeFunction` / `findArchetypeScopeFunction` | `:269/286` | DSL function lookup |

### 17.11 Argument injection (`get<T>()` codegen)

| Symbol | File | Generates |
|--------|------|-----------|
| `KoinArgumentGenerator.generateKoinArgumentForParameter` | `ir/KoinArgumentGenerator.kt:63` | Dispatcher — see §13 priority |
| `createGetPropertyCall` / `createGetPropertyOrNullCall` | `:165/255` | `@Property` |
| `createGetFromNamedScopeCall` | `:309` | `@ScopeId` |
| `createParametersHolderGetCall` / `…OrNull` | `:516/545` | `@InjectedParam` |
| `createScopeGetAllCall` | `:345` | `List<T>` |
| `createScopeGetCall` / `…OrNullCall` | `:378/418` | `scope.get<T>(qualifier)` |
| `createScopeInjectCall` | `:458` | `Lazy<T>` → `scope.inject<T>(...)` |

### 17.12 Safety validation (A2/A3/A3-DSL/A4)

| Symbol | File | Layer |
|--------|------|-------|
| `CompileSafetyValidator.validate` | `ir/CompileSafetyValidator.kt:46` | A2 (per-module) |
| `CompileSafetyValidator.validateFullGraph` | `:92` | A3 (startKoin) |
| `CallSiteValidator.validateDslDefinitionGraph` | `ir/CallSiteValidator.kt:354` | A3-DSL (entry-point, no `<T>`) |
| `CallSiteValidator.validatePendingCallSites` | `:41` | A4 (call sites) |
| `CallSiteValidator.validateCallSiteHintsFromDependencies` | `:272` | A4-deferred (cross-module) |
| `BindingRegistry.validateModule` | `ir/BindingRegistry.kt:107` | The actual matcher |
| `BindingRegistry.findProvider` | `:215` | Type + qualifier + scope match |
| `BindingRegistry.qualifiersMatch` | `:256` | Qualifier equality (with type vs string) |
| `BindingRegistry.reportMissingDependency` | `:268` | Error message + similarity hint |
| `BindingRegistry.isWhitelistedType` | `:79` (companion) | Android framework allowlist |
| `Requirement.requiresValidation` | `ir/BindingRegistry.kt:49` | "Skip if @InjectedParam/@Provided/nullable/List/…" |
| `ParameterAnalyzer.analyzeParameter` | `ir/ParameterAnalyzer.kt:48` | IR-free mirror of `KoinArgumentGenerator` |
| `buildVisibleDefinitions` | `ir/KoinAnnotationProcessor.kt:2148` | A2 visibility set |

### 17.13 Incremental compilation

| Symbol | File | Role |
|--------|------|------|
| `KoinPluginLogger.lookupTracker` | `KoinPluginComponentRegistrar.kt:56` | Global handle |
| `FirKoinLookupRecorder.KoinApplicationLookupChecker.check` | `fir/FirKoinLookupRecorder.kt:56` | FIR-side IC recorder for `@KoinApplication` |
| `trackClassLookup` | `ir/IncrementalTracking.kt:37` | File → class dep |
| `trackLookup` | `:53` | File → declaration dep |
| `linkDeclarationsForIC` | `:77` | Structural-change signal via `ExpectActualTracker` |
| `withLookupTracker` | `:97` | Null-safe + synchronized wrapper |

### 17.14 Registries (compilation-scoped global state)

| Symbol | File | Lifecycle |
|--------|------|-----------|
| `KoinPluginLogger` | `KoinPluginComponentRegistrar.kt:29` | Set by `init`, read everywhere |
| `PropertyValueRegistry.register` / `getDefault` / `hasDefault` / `clear` | `PropertyValueRegistry.kt:31/39/46/53` | Phase 1 writes, Phase 1+ reads |
| `ProvidedTypeRegistry.register` / `isProvided` / `clear` | `ProvidedTypeRegistry.kt:29/37/44` | Phase 1 writes, Phases 1+/3.5/3.6 read |

### 17.15 Constants (`KoinPluginConstants`)

Plugin options:
- `OPTION_USER_LOGS`, `OPTION_DEBUG_LOGS`, `OPTION_UNSAFE_DSL_CHECKS`,
  `OPTION_SKIP_DEFAULT_VALUES`, `OPTION_COMPILE_SAFETY`

Definition types:
- `DEF_TYPE_SINGLE`, `DEF_TYPE_FACTORY`, `DEF_TYPE_SCOPED`, `DEF_TYPE_VIEWMODEL`,
  `DEF_TYPE_WORKER`, `ALL_DEFINITION_TYPES`

Hint prefixes / names:
- `HINTS_PACKAGE`, `HINT_FUNCTION_PREFIX`, `DEFINITION_HINT_PREFIX`,
  `DEFINITION_FUNCTION_HINT_PREFIX`, `COMPONENT_SCAN_HINT_PREFIX`,
  `COMPONENT_SCAN_FUNCTION_HINT_PREFIX`, `COMPONENT_SCAN_FUNCTION_ROSTER_PARAM_PREFIX`,
  `MODULE_DEFINITION_HINT_PREFIX`, `DSL_DEFINITION_HINT_PREFIX`,
  `QUALIFIER_HINT_NAME`, `CALLSITE_HINT_NAME`, `DSL_MODULE_PARAM_PREFIX`

Other:
- `DEFAULT_LABEL = "default"`
- `MODULE_FUNCTION_NAME = "module"`
- `sanitizeQualifierName` / `unsanitizeQualifierName`

### 17.16 `KoinAnnotationFqNames` (all annotation & class FQNames)

See §2 for the grouped overview. The full list is in `KoinAnnotationFqNames.kt` (~268 lines):
module annotations, definition annotations (Koin + JSR-330), scope/archetype annotations,
parameter annotations, application/monitor annotations, core class FQNames,
call-site resolution functions (19 entries), and stdlib FQNames.

### 17.17 Data types

| Type | File | Purpose |
|------|------|---------|
| `ModuleClass` | `ir/AnnotationModels.kt:17` | `@Module` class record |
| `DefinitionClass` | `:28` | `@Singleton class A` record |
| `DefinitionFunction` | `:42` | `@Module fun provide()` record |
| `DefinitionTopLevelFunction` | `:55` | Top-level `@Singleton fun` record |
| `Definition` (sealed) | `:70` | Unified abstraction (`ClassDef`/`FunctionDef`/`TopLevelFunctionDef`/`DslDef`/`ExternalFunctionDef`) |
| `DefinitionType` (enum) | `:150` | `SINGLE`/`FACTORY`/`SCOPED`/`VIEW_MODEL`/`WORKER` |
| `DependencyModuleResult` | `:163` | `(definitions, isComplete)` |
| `TypeKey` | `ir/BindingRegistry.kt:20` | `classId` ∪ `fqName` |
| `Requirement` | `:36` | Single constructor parameter need |
| `ProviderKey` | `:65` | `(typeKey, qualifier, scopeClass)` |
| `QualifierValue` (sealed) | `ir/QualifierExtractor.kt:45` | `StringQualifier`/`TypeQualifier` |
| `PendingCallSiteValidation` | `ir/CallSiteValidator.kt` | Deferred call-site record |
| `KoinModuleFirGenerator.ConfigurationModule` / `ModuleClassInfo` / `DefinitionClassInfo` / `DefinitionFunctionInfo` / `ModuleDefinitionFunctionInfo` / `QualifierAnnotationInfo` | `fir/KoinModuleFirGenerator.kt` | FIR-side discovery records (file-private) |

### 17.18 Logging

| Symbol | File | When |
|--------|------|------|
| `KoinPluginLogger.user { … }` | `KoinPluginComponentRegistrar.kt:81` | `userLogs=true` (component detection) |
| `KoinPluginLogger.debug { … }` | `:96` | `debugLogs=true` (internals) |
| `KoinPluginLogger.warn(msg)` | `:106` | Always |
| `KoinPluginLogger.error(msg)` | `:138` | Always (fails compile) |
| `KoinPluginLogger.error(msg, file, line, col)` | `:146` | Same with source location |
| `KoinPluginLogger.userFir { … }` | `:116` | FIR-phase user log |
| `KoinPluginLogger.debugFir { … }` | `:128` | FIR-phase debug log |

> **Performance**: All `user`/`debug`/`userFir`/`debugFir` are `inline` with lambda
> parameters — the lambda body is never evaluated when logging is disabled. Use `{}` syntax,
> not `(…)`.
