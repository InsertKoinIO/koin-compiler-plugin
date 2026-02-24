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

Validation runs at three levels, each widening what is visible:

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

Mark a type as externally available at runtime. The safety checker skips it during validation.

```kotlin
@Provided
class SavedStateHandle  // always available via Android framework

@Singleton
class MyViewModel(val handle: SavedStateHandle)
// No error — SavedStateHandle is @Provided
```

`@Provided` is collected during Phase 1 annotation scanning and stored in `ProvidedTypeRegistry`.

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
| `kotlinx.coroutines.CoroutineDispatcher` | Kotlin coroutines |
| `kotlinx.coroutines.CoroutineScope` | Kotlin coroutines |

The whitelist is defined in `BindingRegistry.WHITELISTED_TYPES`.

Both `@Provided` and the whitelist are checked before reporting a missing dependency. If either matches, the type is considered satisfied.

## Configuration

```kotlin
koinCompiler {
    safetyChecks = true   // Enable/disable compile-time safety checks (default: true)
}
```

Safety checks are gated by `KoinPluginLogger.safetyChecksEnabled`, controlled by the `safetyChecks` Gradle option.

---

# Implementation

## Architecture Overview

Validation runs during the IR phase (Phase 1: `KoinAnnotationProcessor`), after definitions are collected but before module function bodies are generated. A3 validation additionally runs in Phase 3 (`KoinStartTransformer`).

```
IR Phase 1: KoinAnnotationProcessor
  ├── collectAnnotations()              → discover @Module, @Singleton, @Provided, etc.
  ├── generateModuleExtensions()        → for each module:
  │   ├── collect local definitions
  │   ├── collect cross-module definitions (hints)
  │   ├── A1: add definitions from includes
  │   ├── A2: add definitions from @Configuration siblings
  │   ├── BindingRegistry.validateModule()   ← validation happens here
  │   └── generate module() function body
  └── expose: collectedModuleClasses, getDefinitionsForModule()

IR Phase 3: KoinStartTransformer
  ├── visitCall(startKoin<T>)           → extract @KoinApplication modules
  ├── A3: validateFullGraph()           → validate ALL modules combined
  └── transform to startKoinWith(modules, lambda)
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
    class ClassDef(val definitionClass: DefinitionClass)
    class FunctionDef(val definitionFunction: DefinitionFunction)
    class TopLevelFunctionDef(val definitionTopLevelFunction: DefinitionTopLevelFunction)

    abstract val definitionType: DefinitionType
    abstract val returnTypeClass: IrClass   // the provided type
    abstract val bindings: List<IrClass>    // auto-bound interfaces
    abstract val scopeClass: IrClass?       // scope, if scoped
}
```

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
if (KoinPluginLogger.safetyChecksEnabled && moduleClasses.isNotEmpty() && annotationProcessor != null) {
    validateFullGraph(appClass, moduleClasses)
}
```

`validateFullGraph()` collects ALL definitions from ALL modules via `annotationProcessor.getDefinitionsForModule()` and runs `BindingRegistry.validateModule()` on the union.

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
| C | Cross-Gradle-module (definitions from dependency JARs via hints) | Done |
| C2 | Cross-module function hint metadata (qualifier, scope, bindings) | Not started |
| B | DSL calls (`single<T>()`, `factory<T>()`) | Not started |
| D | `@Property`/`@PropertyValue` matching | Not started |

### Phase C: Known Limitations

Cross-module **class** definitions have full metadata (annotations are available from JAR metadata). Cross-module **top-level function** definitions use `ExternalFunctionDef` which is provider-only — the hint carries only the return type. The following metadata is not yet propagated:

| Metadata | Effect | Consequence |
|----------|--------|-------------|
| `@Named`/`@Qualifier` | Always `null` | Qualified dependency may falsely pass (matches unqualified provider) |
| `@Scope(MyScope::class)` | Always root-scope | Scoped function appears visible everywhere (false positive) |
| `@Bind(Interface::class)` | Always empty | Consumers depending on the bound interface won't find it |

Additionally, package filtering for function hints is based on the **return type's package**, not the function's own package. If `@Singleton fun provideRepo(): Repository` is in package `infra` but `Repository` is in package `domain`, the `@ComponentScan` must match `domain`.
