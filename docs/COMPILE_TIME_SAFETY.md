# Compile-Time Dependency Validation

Detect missing dependencies at compile time instead of runtime crashes.

## The Problem

Without compile-time safety, a missing dependency only surfaces at runtime:

```kotlin
@Module @ComponentScan
class AppModule

@Singleton
class MyService(val repo: Repository)
// Repository is never declared → compiles fine, crashes at runtime:
//   "No definition found for class 'Repository'"
```

## What Gets Validated

| Scenario | Result |
|----------|--------|
| Non-nullable param, no definition | **ERROR** |
| Nullable param (`T?`), no definition | OK — uses `getOrNull()` |
| Param with default value, no definition | OK — uses Kotlin default (when `skipDefaultValues=true`) |
| `@InjectedParam`, no definition | OK — provided at runtime via `parametersOf()` |
| `@Property("key")` param | OK — property injection, not DI validation |
| `List<T>` param | OK — `getAll()` returns empty list if none |
| `Lazy<T>`, no definition for `T` | **ERROR** — unwraps to validate inner type |
| `@Named("x")` param, no matching qualifier | **ERROR** — with hint if unqualified binding exists |
| Scoped dependency from wrong scope | **ERROR** |
| Default value param with `@Named` qualifier | **ERROR** — qualifier forces injection |
| `@Provided` type, no definition | OK — externally provided at runtime |
| Android framework type (e.g. `Context`) | OK — hardcoded whitelist |

## Validation Scopes

Validation runs at multiple levels, each widening what is visible:

### A1: Per-Module (local + includes)

Each `@Module` is validated against its own definitions plus explicitly included modules.

```kotlin
@Module(includes = [DataModule::class])
@ComponentScan("app")
class AppModule
// Validates: definitions from AppModule + DataModule
```

### A2: Configuration Group (same @Configuration label)

Modules sharing a `@Configuration` label are loaded together at runtime. Their definitions are mutually visible during validation.

```kotlin
@Module @ComponentScan("core") @Configuration("prod")
class CoreModule  // provides Repository

@Module @ComponentScan("service") @Configuration("prod")
class ServiceModule  // Service(repo: Repository) → OK, Repository visible from CoreModule
```

Different labels are isolated:
```kotlin
@Configuration("core")   // ← "core" label
class CoreModule

@Configuration("service") // ← "service" label — different, CoreModule NOT visible
class ServiceModule       // Service(repo: Repository) → ERROR
```

### A3: startKoin Entry Point (full graph)

When `startKoin<T>()` is used with `@KoinApplication`, the full assembled graph is validated.

```kotlin
@KoinApplication(modules = [CoreModule::class, ServiceModule::class])
object MyApp

startKoin<MyApp> { }
// Validates: ALL definitions from CoreModule + ServiceModule combined
```

### A4: Call-Site Validation

Validates resolution call sites: `get<T>()`, `inject<T>()`, `koinViewModel<T>()`, etc. These are calls outside of module definitions that resolve a type from the DI container at runtime.

```kotlin
// In an Activity or Fragment:
val service: MyService by inject()
// Validates: MyService is available in the assembled graph
```

Call sites are collected during Phase 2 (`KoinDSLTransformer.collectCallSiteIfResolutionFunction`) and validated in Phase 3.5 against the assembled graph, DSL definitions, and dependency hints.

When a call site cannot be resolved locally (e.g., in a feature module without the full graph), it generates a `callsite(required: T)` hint function for deferred validation. The app module discovers and validates these hints in Phase 3.6.

### Phase 3.1: DSL-Only A3

When `startKoin { }` is present but no `startKoin<T>()` or `@KoinApplication` is used, Phase 3.1 performs A3-style validation on DSL definitions only. It validates constructor parameters of local DSL definitions (`single<T>()`, `factory<T>()`, etc.) against all known providers (local DSL + dependency DSL hints + annotation definitions). This catches missing definitions like commenting out `single<Repository>()` that a ViewModel needs.

Only runs in the entry-point module (the one that calls `startKoin { }`) to avoid false positives in leaf modules that don't have the full graph visible.

### Phase 3.5: Pending Call-Site Validation with Deferred Hint Generation

After all definitions are collected and `startKoin` is processed, pending call sites are validated against the combined set of assembled graph types, DSL definitions, and DSL hints from dependencies.

Unresolved call sites in modules without a full graph generate `callsite(required: T)` hint functions in `org.koin.plugin.hints`. These hints are synthetic IR functions that encode the required type as a parameter, allowing downstream modules to discover and validate them.

### Phase 3.6: Cross-Module Call-Site Hint Validation

The app module (or any module with all definitions visible) discovers call-site hints from dependency modules via `context.referenceFunctions(callsite)` and validates each required type against the full set of known definitions. This completes the deferred validation started in Phase 3.5.

## Error Messages

Errors report the missing type, which definition needs it, and in which module:

```
[Koin] Missing dependency: Repository
  required by: Service (parameter 'repo')
  in module: ServiceModule
```

When a binding exists with a different qualifier, a hint is shown:

```
[Koin] Missing dependency: NetworkClient (qualifier: @Named("http"))
  required by: ApiService (parameter 'client')
  in module: AppModule
  Hint: Found NetworkClient without qualifier — did you mean to add @Named("http")?
```

For A3 validation, the application name is used:

```
[Koin] Missing dependency: MissingDep
  required by: Service (parameter 'missing')
  in module: MyApp (startKoin)
```

## External Types: `@Provided` and Whitelist

Some types are provided by the platform or framework at runtime (e.g., Android's `Context`, `SavedStateHandle`) and are never declared as Koin definitions. Without special handling, these would trigger false "missing dependency" errors.

Two mechanisms prevent this:

### `@Provided` Annotation

Mark a type or parameter as externally available at runtime. The safety checker skips it during validation.

Can be used on a **class** (all usages of that type are skipped) or on a **parameter** (only that specific parameter is skipped):

```kotlin
// Class-level: all usages of SavedStateHandle skip validation
@Provided
class SavedStateHandle

// Parameter-level: only this specific parameter is skipped
@Singleton
class MyService(@Provided val ctx: PlatformContext)
```

Class-level `@Provided` is collected during Phase 1 annotation scanning and stored in `ProvidedTypeRegistry`.
Parameter-level `@Provided` is detected in `ParameterAnalyzer` and marks the `Requirement` as not requiring validation.

### Hardcoded Framework Whitelist

Common Android framework types are always skipped, without requiring `@Provided`:

| Type | Source |
|------|--------|
| `android.content.Context` | Android core |
| `android.app.Activity` | Android core |
| `android.app.Application` | Android core |
| `androidx.fragment.app.Fragment` | AndroidX |
| `androidx.lifecycle.SavedStateHandle` | AndroidX |
| `androidx.work.WorkerParameters` | AndroidX |

The whitelist is defined in `BindingRegistry.WHITELISTED_TYPES`.

Both `@Provided` and the whitelist are checked before reporting a missing dependency. If either matches, the type is considered satisfied.

## Special Parameter Handling

### `Scope` Parameter Injection

Parameters of type `org.koin.core.scope.Scope` are injected with the scope receiver itself (not resolved via `scope.get<Scope>()`). Validation is automatically skipped.

```kotlin
@Scoped
class ScopedService(val scope: Scope) {
    fun dynamicLookup() = scope.get<SomeDep>()  // Not validated at compile time
}
// Generates: ScopedService(scope)  — passes the scope receiver directly
```

### `@ScopeId` — Named Scope Resolution

Parameters annotated with `@ScopeId` are resolved from a named Koin scope. Validation is skipped since the scope is resolved at runtime.

Supports two forms:
- `@ScopeId(name = "my_scope")` — string-based scope ID
- `@ScopeId(MyScope::class)` — type-based scope ID (uses FQ class name)

```kotlin
@Factory
class ProfileService(@ScopeId(name = "user_session") val session: UserSession)
// Generates: ProfileService(scope.getScope("user_session").get<UserSession>())
```

### `@Property`/`@PropertyValue` Validation

The compiler warns when `@Property("key")` has no matching `@PropertyValue("key")` default in the same compilation unit. This is a **warning** (not error) since properties can be set at runtime via `properties()`.

```kotlin
@PropertyValue("api.timeout")
val defaultTimeout = 30

@Factory
class ApiClient(@Property("api.timeout") val timeout: Int)
// OK — @PropertyValue("api.timeout") provides a default

@Factory
class Other(@Property("missing.key") val value: String)
// WARNING — no @PropertyValue("missing.key") found
```

## Module Load Order and Overrides

Koin is **last-wins** at runtime: when two modules define the same type, the one loaded last takes precedence. The compiler plugin assembles the module list at the `@KoinApplication` root in this order:

1. **Auto-discovered `@Configuration` modules** (this compilation + dependency JARs) — load first
2. **Explicit `@KoinApplication(modules = [A, B, C])`** — load last, **in declaration order**

The rationale: apps customise libraries, not the other way round. So the app's explicit list wins over dependency-provided defaults.

```kotlin
// Dependency JAR — default implementation
@Module @Configuration
class CoreModule {
    @Singleton fun feature(): Feature = DefaultFeature()
}

// App — custom override
@Module
class AppModule {
    @Singleton fun feature(): Feature = AppFeature()
}

@KoinApplication(modules = [AppModule::class])
class MyApp
// Load order: CoreModule (DefaultFeature) → AppModule (AppFeature wins)
```

Within the explicit list, declaration order is preserved:

```kotlin
@KoinApplication(modules = [A::class, B::class, C::class])
// Load order: (@Configuration deps) → A → A.includes → B → B.includes → C → C.includes
// Winner among A/B/C: C (declared last)
```

If a module re-appears in both the explicit list and is also discovered via `@Configuration`, it is loaded once — at its **explicit position** — so the user's declaration order always controls override precedence.

**Escape hatch for fine-grained ordering**: list all participating modules explicitly in `@KoinApplication(modules = [...])` in the desired order. This bypasses classpath-dependent discovery order for `@Configuration` modules.

## Generic DSL Types

Runtime Koin resolves definitions on the **erased raw class** — type parameters are not part of the lookup key. Compile-safety honours that: a `get<Box<X>>()` call is validated against any `Box<*>` provider in the graph, and two `single<Box<A>>()` / `single<Box<B>>()` declarations collide on the same raw class.

```kotlin
val appModule = module {
    single<Navigator<AppKey>>()     // validated as Navigator (raw)
    single<Navigator<MenuKey>>()    // treated as the same definition
}
```

Validating on the raw class is also what makes iOS/Native builds work — emitting the generic type with its free parameter into hint functions used to crash the Kotlin/Native klib signature mangler.

### Discriminating generic instances — use `named<T>()`

When multiple instances of the same generic class must coexist, register a **concrete wrapper type** and key each instance with a type qualifier derived from the generic parameter. This is the pattern used internally by `koin-compose-navigation3`:

```kotlin
// From koin-compose-navigation3
inline fun <reified T : Any> Module.navigation(
    noinline definition: @Composable Scope.(T) -> Unit,
): KoinDefinition<EntryProviderInstaller> {
    // Concrete type EntryProviderInstaller + type qualifier derived from T.
    return _singleInstanceFactory<EntryProviderInstaller>(named<T>(), { ... })
}

// Caller side
module {
    navigation<HomeRoute> { ... }      // keyed by named<HomeRoute>()
    navigation<SettingsRoute> { ... }  // keyed by named<SettingsRoute>()
}

// Resolution uses the same type qualifier
koin.get<EntryProviderInstaller>(named<HomeRoute>())
```

Runtime Koin matches on (raw class + qualifier), and the compile-safety validator respects qualifier matching — so the plugin sees these as two distinct definitions, as expected. Prefer `named<T>()` qualifier-on-concrete-type over `single<Box<X>>()` directly whenever you need to distinguish generic instantiations.

## Configuration

```kotlin
koinCompiler {
    compileSafety = true   // Enable/disable compile-time safety checks (default: true)
}
```

Safety checks are gated by `KoinPluginLogger.compileSafetyEnabled`, controlled by the `compileSafety` Gradle option.

---

# Implementation

## Architecture Overview

Validation runs across multiple IR phases. A1/A2 validation happens in Phase 1b, A3 in Phase 3/3.1, and A4 in Phase 3.5/3.6.

```
IR Phase 0: KoinHintTransformer
  └── Generate bodies for FIR-created hint functions

IR Phase 1: KoinAnnotationProcessor.collectAnnotations()
  └── Discover @Module, @Singleton, @Provided, etc.

IR Phase 1b: KoinAnnotationProcessor.generateModuleExtensions()
  ├── For each module:
  │   ├── collect local definitions
  │   ├── collect cross-module definitions (hints)
  │   ├── A1: add definitions from includes
  │   ├── A2: add definitions from @Configuration siblings
  │   ├── BindingRegistry.validateModule()   ← A1/A2 validation
  │   └── generate module() function body
  └── expose: collectedModuleClasses, getDefinitionsForModule()

IR Phase 2: KoinDSLTransformer
  ├── Transform single<T>() → single(T::class, null) { T(get()) }
  ├── Collect DslDef definitions for safety graph
  └── Collect PendingCallSiteValidation for A4

IR Phase 2.5: generateDslDefinitionHints()
  └── Generate dsl_single/dsl_factory/... hint functions for cross-module DSL discovery

IR Phase 3: KoinStartTransformer
  ├── visitCall(startKoin<T>)           → extract @KoinApplication modules
  ├── A3: validateFullGraph()           → validate ALL modules combined (incl. DSL definitions)
  └── transform to startKoinWith(modules, lambda)

IR Phase 3.1: validateDslDefinitionGraph()
  └── DSL-only A3 — when startKoin{} exists but no startKoin<T>() / @KoinApplication

IR Phase 3.5: validatePendingCallSites()
  ├── A4: validate get<T>(), inject<T>(), koinViewModel<T>() call sites
  └── Generate callsite(required: T) hints for unresolved types (deferred validation)

IR Phase 3.6: validateCallSiteHintsFromDependencies()
  └── Discover and validate call-site hints from dependency modules

IR Phase 4: KoinMonitorTransformer
  └── Process @Monitor annotations
```

## Key Components

### BindingRegistry (`ir/BindingRegistry.kt`)

The validation engine. `validateModule()` is self-contained — it builds provided types from the definitions passed in, so it can be called per-module or on a combined graph.

**Data types:**

```kotlin
// Identifies a type in the DI container
data class TypeKey(
    val classId: ClassId?,    // for cross-module matching
    val fqName: FqName?       // for display and fallback matching
)

// A parameter that needs a dependency
data class Requirement(
    val typeKey: TypeKey,
    val paramName: String,
    val isNullable: Boolean,
    val hasDefault: Boolean,
    val isInjectedParam: Boolean,
    val isLazy: Boolean,
    val isList: Boolean,
    val isProperty: Boolean,
    val qualifier: QualifierValue?
)

// A definition that provides a type
data class ProvidedBinding(
    val typeKey: TypeKey,
    val qualifier: QualifierValue?,
    val scopeClass: IrClass?,
    val bindings: List<TypeKey>,      // auto-bound interfaces
    val requirements: List<Requirement>,
    val sourceName: String
)
```

**Core method — `validateModule()`:**

```
validateModule(moduleName, definitions, parameterAnalyzer, qualifierExtractor)
  │
  ├── 1. Build provided types set
  │   For each definition:
  │     ├── add definition's own type (e.g. Repository)
  │     └── add auto-bound interfaces (e.g. IRepository)
  │
  ├── 2. Validate each definition's requirements
  │   For each definition → for each constructor parameter:
  │     ├── ParameterAnalyzer classifies it as Requirement
  │     ├── Requirement.requiresValidation() filters out safe params
  │     ├── skip if @Provided (ProvidedTypeRegistry) or whitelisted (WHITELISTED_TYPES)
  │     └── findProvider() searches the provided set
  │         ├── match by FqName or ClassId
  │         ├── match qualifier (StringQualifier or TypeQualifier)
  │         └── check scope visibility
  │
  └── 3. Report missing dependencies
      └── reportMissingDependency() with hints for similar bindings
```

**Scope visibility rules:**
- Root-scope providers (no `@Scope`) → visible to all consumers
- Same-scope providers → visible within their scope
- Cross-scope → **not visible** (ERROR)

### ParameterAnalyzer (`ir/ParameterAnalyzer.kt`)

Converts IR function/constructor parameters into `Requirement` objects. Mirrors `KoinArgumentGenerator` logic but produces data instead of IR code.

Classification rules:
- `@InjectedParam` → `isInjectedParam=true` → skip validation
- `@Property("key")` → `isProperty=true` → skip validation
- `Lazy<T>` → `isLazy=true`, unwraps to `T` for type matching
- `List<T>` → `isList=true` → skip validation
- `T?` → `isNullable=true` → skip validation
- Default value + no qualifier + `skipDefaultValues` → skip validation
- Everything else → **requires validation**

### ProvidedTypeRegistry (`ProvidedTypeRegistry.kt`)

Stores FQ names of types annotated with `@Provided`. Checked during validation alongside the hardcoded whitelist. Populated during Phase 1 (`collectAnnotations()`), cleared between compilation units.

### QualifierExtractor (`ir/QualifierExtractor.kt`)

Reads qualifier annotations from parameters and definitions. Returns `QualifierValue`:

```kotlin
sealed class QualifierValue {
    data class StringQualifier(val name: String)   // @Named("x"), @Qualifier(name="x")
    data class TypeQualifier(val irClass: IrClass)  // @Qualifier(MyType::class)
}
```

Supports: `@Named` (Koin, jakarta, javax), `@Qualifier` (Koin), and custom qualifier annotations.

### ConfigurationUtils (`ir/ConfigurationUtils.kt`)

Shared utility for reading `@Configuration` labels from IR classes. Used by both A2 (in `KoinAnnotationProcessor`) and the `KoinStartTransformer` for configuration discovery.

```kotlin
fun extractConfigurationLabels(irClass: IrClass): List<String>
// @Configuration("a", "b") → ["a", "b"]
// @Configuration            → ["default"]
// No annotation             → []
```

### AnnotationModels (`ir/AnnotationModels.kt`)

Unified `Definition` sealed class enables polymorphic handling:

```kotlin
sealed class Definition {
    class ClassDef(val irClass: IrClass, ...)              // annotated class
    class FunctionDef(val irFunction: IrSimpleFunction, ...) // annotated function in @Module
    class TopLevelFunctionDef(val irFunction: IrSimpleFunction, ...) // annotated top-level function
    class DslDef(val irClass: IrClass, ...)                // DSL definition (single<T>, factory<T>)
    class ExternalFunctionDef(...)                          // cross-module function from hints

    abstract val definitionType: DefinitionType
    abstract val returnTypeClass: IrClass   // the provided type
    abstract val bindings: List<IrClass>    // auto-bound interfaces
    abstract val scopeClass: IrClass?       // scope, if scoped
}
```

- **DslDef** — collected during Phase 2 (`KoinDSLTransformer`) when DSL calls like `single<T>()` or `factory<T>()` are transformed. Participates in A3 (Phase 3/3.1) and A4 (Phase 3.5) validation as both provider and consumer.
- **ExternalFunctionDef** — provider-only definition discovered from cross-module function hints. Represents a tagged top-level function (`@Singleton fun provide...()`) from another Gradle module. Only contributes to the provided types set; its own requirements were validated in its source module.

## A2: Configuration Group Validation

In `KoinAnnotationProcessor.generateModuleExtensions()`, after collecting local definitions and includes:

```kotlin
// A2: If this module is @Configuration, include sibling modules from the same group
val configLabels = extractConfigurationLabels(moduleClass.irClass)
if (configLabels.isNotEmpty()) {
    val siblingModuleNames = KoinConfigurationRegistry.getModuleClassNamesForLabels(configLabels)
    for (siblingName in siblingModuleNames) {
        val siblingModule = moduleClasses.find {
            it.irClass.fqNameWhenAvailable?.asString() == siblingName
        }
        if (siblingModule != null && siblingModule != moduleClass) {
            allVisibleDefinitions.addAll(collectAllDefinitions(siblingModule))
        }
    }
}
```

`KoinConfigurationRegistry` is a System property-based registry populated during FIR phase. It maps labels to module FQ names, surviving the classloader boundary between FIR and IR.

## A3: startKoin Full-Graph Validation

In `KoinStartTransformer.visitCall()`, after discovering all modules from `@KoinApplication`:

```kotlin
if (KoinPluginLogger.compileSafetyEnabled && moduleClasses.isNotEmpty() && annotationProcessor != null) {
    validateFullGraph(appClass, moduleClasses)
}
```

`validateFullGraph()` collects ALL definitions from ALL modules via `annotationProcessor.getDefinitionsForModule()`, includes DSL definitions (`DslDef`) passed from Phase 2, and runs `BindingRegistry.validateModule()` on the union.

The `annotationProcessor` reference is passed from `KoinIrExtension` (Phase 1 → Phase 3).

## Test Coverage

### Unit Tests

- `BindingRegistryTest` — 26 tests covering: type matching, qualifier matching, scope visibility, nullable/lazy/list/injectedParam/default skipping, missing dependency detection
- `KoinAnnotationFqNamesTest` — annotation FQName correctness

### Box Tests (runtime verification)

In `testData/box/safety/`:

| Test | Validates |
|------|-----------|
| `complete_graph.kt` | All deps satisfied → no error, runs OK |
| `nullable_ok.kt` | Nullable params skip validation |
| `injected_param_ok.kt` | `@InjectedParam` skips validation |
| `default_value_ok.kt` | Default values skip validation |
| `lazy_valid.kt` | `Lazy<T>` with T available → OK |
| `list_ok.kt` | `List<T>` skips validation |
| `qualifier_match.kt` | `@Named` qualifier matching works |
| `scoped_visibility.kt` | Scope visibility rules |
| `module_includes_visible.kt` | A1: included modules expand visibility |
| `configuration_group.kt` | A2: `@Configuration` siblings share definitions |
| `startkoin_full_graph.kt` | A3: `startKoin<T>` validates full graph |

### Diagnostic Tests (compilation error verification)

In `testData/diagnostics/`:

| Test | Validates |
|------|-----------|
| `missing_dependency.kt` | Missing non-nullable dep → ERROR |
| `lazy_missing.kt` | `Lazy<T>` with T missing → ERROR |
| `qualifier_mismatch.kt` | Wrong qualifier → ERROR with hint |
| `scoped_cross_scope.kt` | Cross-scope dependency → ERROR |
| `configuration_label_mismatch.kt` | Different `@Configuration` labels → not visible → ERROR |
| `startkoin_missing.kt` | A3: full graph still missing dep → ERROR |

Each diagnostic test has `.fir.txt` (FIR golden file) and `.errors.txt` (error message golden file) for regression testing.

## Current Status and Roadmap

| Phase | Scope | Status |
|-------|-------|--------|
| A1 | Per-module (local + includes) | Done |
| A2 | `@Configuration` group siblings | Done |
| A3 | `startKoin<T>` full graph | Done |
| A4 | Call-site validation (`get<T>()`, `inject<T>()`, `koinViewModel<T>()`) | Done |
| B | DSL calls (`single<T>()`, `factory<T>()`) in safety graph | Done |
| C | Cross-Gradle-module (definitions from dependency JARs via hints) | Done |
| C2 | Cross-module function hint metadata (qualifier, scope, bindings) | Done |
| D | `@Property`/`@PropertyValue` matching | Done |

**Phase B notes:** DSL definitions (`single<T>()`, `factory<T>()`, etc.) are collected as `DslDef` during Phase 2 and participate in the safety graph. Phase 3.1 validates their constructor parameters when no `startKoin<T>()` / `@KoinApplication` is present. Phase 2.5 generates DSL definition hints (`dsl_single`, `dsl_factory`, etc.) for cross-module discovery.

**Phase A4 notes:** Call sites are collected during Phase 2 and validated in Phase 3.5. Unresolved call sites in feature modules generate `callsite(required: T)` hint functions for deferred validation by the app module in Phase 3.6.

### Phase C: Known Limitations

Cross-module **class** definitions have full metadata (annotations are available from JAR metadata). Cross-module **top-level function** definitions use `ExternalFunctionDef` with metadata encoded in hint function parameters (C2):

| Metadata | Encoding | Status |
|----------|----------|--------|
| `@Named`/`@Qualifier` | `qualifier_<name>` or `qualifierType` hint param | Done |
| `@Scope(MyScope::class)` | `scope` hint parameter | Done |
| Bindings (supertypes) | `binding0`, `binding1`, ... hint params | Done |

**Remaining limitation:** Package filtering for function hints is based on the **return type's package**, not the function's own package. If `@Singleton fun provideRepo(): Repository` is in package `infra` but `Repository` is in package `domain`, the `@ComponentScan` must match `domain`.
