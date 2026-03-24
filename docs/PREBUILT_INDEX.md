# Prebuilt Module Index

Design document for the `prebuiltIndex` feature — compile-time prebuilding of Koin's instance registry.

## Goal

Instant startup. Like Dagger/Metro, the dependency graph is fully built at compile time. At startup, the prebuilt `_instances` map is assigned in one shot — no iteration, no per-module processing, no map building. The compiler knows everything: types, qualifiers, scopes, constructor calls, bindings, IndexKey strings. Today it generates imperative DSL calls that rebuild all of this at runtime. With `prebuiltIndex`, nothing is computed at startup.

## Three Usage Modes

| | Without Compiler Plugin | Compiler Plugin (prebuiltIndex = false) | Compiler Plugin (prebuiltIndex = true) |
|---|---|---|---|
| **Definitions** | `module { single { MyService(get()) } }` | `@Singleton class MyService` or `single<MyService>()` | Same — `@Singleton` or `single<MyService>()` |
| **Modules** | `val m = module { ... }` | `@Module class AppModule` or `module { single<T>() }` | Same |
| **Startup** | `startKoin { modules(m) }` | `startKoin<MyApp> { }` or `startKoin { modules(m) }` | Same |
| **Hand-written DSL** | Works | Works | **Compiler error** — must use typed DSL |
| **At startup** | Full DSL evaluation + map building | Full DSL evaluation + map building | Map assignment |

## Gradle Configuration

```kotlin
koinCompiler {
    prebuiltIndex = false  // default in 1.0
}
```

| Version | Default | Status |
|---------|---------|--------|
| **1.0** | `false` | Ships, opt-in |
| **1.x** | `false` | Gather feedback, stabilize |
| **2.0** | `true` | Default on, major version bump |

### Behavior When Enabled

| Path | `prebuiltIndex = true` | `prebuiltIndex = false` |
|---|---|---|
| Annotations (`@Module`) | Prebuilt index. Always safe. | Existing `buildSingle()` path |
| DSL — all safe (`single<T>()`) | Prebuilt index | Existing `buildSingle()` path |
| DSL — has unsafe (`single { }`) | **Compiler error** | Existing path (unsafe untouched) |

Compiler error when unsafe DSL detected:
```
e: Module contains hand-written definitions incompatible with prebuilt index.
   Convert to typed DSL (single<T>(), factory<T>()) or disable:
   koinCompiler { prebuiltIndex = false }
```

## Current Runtime Loading Path

```
startKoin { modules(m1, m2) }
  → KoinApplication.modules(list)
  → Koin.loadModules(modules, allowOverride)
  → flatten(modules)                          // DFS on module includes
  → for each module:                           // M iterations
  →   instanceRegistry.loadModule(module)      // copy N mappings per module
  → scopeRegistry.loadScopes(flatModules)
```

### What Happens Today (per definition)

Both annotation-based (`.module()`) and DSL (`single<T>()`) paths converge on `createDefinition()`. Each call:

1. Creates `BeanDefinition<T>(scopeQualifier, primaryType, qualifier, definition, kind, secondaryTypes)`
2. Wraps in `InstanceFactory` (`SingleInstanceFactory` / `FactoryInstanceFactory` / `ScopedInstanceFactory`)
3. Computes `IndexKey` string via `indexKey("type:qualifier:scope")`
4. Stores in `Module.mappings: LinkedHashMap<IndexKey, InstanceFactory<*>>`

Then `InstanceRegistry.loadModules()` iterates M modules, copying each module's N mappings into `_instances`.

All of this happens at startup, blocking app initialization.

### What the Compiler Already Knows

At compile time, for every definition, the plugin has:

| Information | Source | Example |
|------------|--------|---------|
| Primary type | Type parameter or return type | `MyService::class` |
| Kind | DSL function or annotation | `Singleton`, `Factory`, `Scoped` |
| Qualifier | `@Named("x")`, `@Qualifier` | `named("http")` |
| Scope qualifier | `@Scope(MyScope::class)` or DSL context | `typeQualifier<MyScope>()` |
| Constructor call | Primary constructor analysis | `MyService(get(), get(named("x")))` |
| Secondary types (bindings) | Supertype analysis | `listOf(IService::class)` |
| IndexKey string | FQN + qualifier + scope | `"org.example.MyService:null:_root_"` |
| Eager flag | `createdAtStart` parameter | `true` / `false` |

**None of this needs to be computed at startup.**

## Design

### Core Idea

Each module carries a prebuilt `KoinIndex` — a pre-populated `HashMap<String, InstanceFactory<*>>` with all definitions already resolved. At startup, indexes are merged and the map is assigned directly to `_instances`. No iteration over definitions. No map building. One assignment.

This is the same model as Dagger/Metro: the dependency graph is fully constructed at compile time. The only difference is that Koin keeps a HashMap for its `get<T>()` API (O(1) lookup), while Dagger uses direct factory calls. The lookup is cheap — the bottleneck was always building the map, which we now eliminate from startup.

### koin-core: KoinIndex — a pre-populated map

```kotlin
// org.koin.core.index

/**
 * A prebuilt module index — a pre-populated instances map.
 * Built at module initialization time (class loading), not at startup.
 * At startup, the map is assigned directly to InstanceRegistry._instances.
 */
class KoinIndex(
    val instances: HashMap<String, InstanceFactory<*>>,
    val scopes: Set<Qualifier> = emptySet(),
    val eagerInstances: List<InstanceFactory<*>> = emptyList()
) {
    operator fun plus(other: KoinIndex): KoinIndex = KoinIndex(
        instances = HashMap<String, InstanceFactory<*>>(
            this.instances.size + other.instances.size
        ).apply {
            putAll(this@KoinIndex.instances)
            putAll(other.instances)
        },
        scopes = this.scopes + other.scopes,
        eagerInstances = this.eagerInstances + other.eagerInstances
    )
}
```

### koin-core: buildKoinIndex + buildEntry

```kotlin
// org.koin.core.index

/** Create a KoinIndex and populate it via builder. Called by compiler-generated code. */
inline fun buildKoinIndex(builder: KoinIndex.() -> Unit): KoinIndex =
    KoinIndex(HashMap()).apply(builder)

// org.koin.plugin.module.dsl (alongside existing buildSingle, buildFactory...)

/**
 * Prebuild a definition entry directly into the KoinIndex map.
 * Creates BeanDefinition + InstanceFactory + IndexKey and inserts into the map.
 * One function for all definition kinds — Kind parameter selects the factory type.
 *
 * Follows the same pattern as existing buildSingle/buildFactory/etc.
 */
fun <T : Any> KoinIndex.buildEntry(
    kclass: KClass<T>,
    kind: Kind,
    qualifier: Qualifier?,
    scopeQualifier: Qualifier,
    secondaryTypes: List<KClass<*>>,
    createdAtStart: Boolean = false,
    definition: Definition<T>
) {
    val def = BeanDefinition(scopeQualifier, kclass, qualifier, definition, kind, secondaryTypes)
    val factory = when (kind) {
        Kind.Singleton -> SingleInstanceFactory(def)
        Kind.Factory -> FactoryInstanceFactory(def)
        Kind.Scoped -> ScopedInstanceFactory(def)
    }
    val key = indexKey(kclass, qualifier, scopeQualifier)
    instances[key] = factory
    for (type in secondaryTypes) {
        instances[indexKey(type, qualifier, scopeQualifier)] = factory
    }
    if (createdAtStart) {
        eagerInstances += factory
    }
}
```

### koin-core: indexedModule

```kotlin
// org.koin.core.index

/** Create a Module with only .index set. No lambda, no mappings. */
fun indexedModule(index: KoinIndex): Module =
    Module().apply { this.index = index }

/** Create a Module with .index set and includes preserved (for DSL modules). */
fun indexedModule(index: KoinIndex, includes: List<Module>): Module =
    Module().apply { this.index = index; this.includes(includes) }
```

### koin-core: Module.index

```kotlin
// Module class — one new field
class Module {
    // ... all existing fields unchanged ...
    var index: KoinIndex? = null  // NEW — filled by compiler plugin
}
```

### koin-core: loadIndex — instant loading

```kotlin
// InstanceRegistry — one new method
fun InstanceRegistry.loadIndex(index: KoinIndex, allowOverride: Boolean) {
    if (allowOverride || _instances.isEmpty()) {
        _instances.putAll(index.instances)
    } else {
        for ((key, factory) in index.instances) {
            saveMapping(allowOverride, key, factory)
        }
    }
    for (factory in index.eagerInstances) {
        eagerInstances[factory.hashCode()] = factory
    }
}

// KoinApplication — one new method
fun KoinApplication.modulesIndex(index: KoinIndex) {
    koin.instanceRegistry.loadIndex(index, koin.allowOverride)
    koin.scopeRegistry.loadScopes(index.scopes)
}
```

When `_instances` is empty (typical fresh startup), `loadIndex()` is just one `putAll` — the JVM optimizes this as a bulk HashMap copy.

### koin-core: Startup Functions

```kotlin
// org.koin.plugin.module.dsl (alongside existing startKoinWith...)

fun startKoinWithIndex(index: KoinIndex, config: KoinAppDeclaration? = null)
fun koinApplicationWithIndex(index: KoinIndex, config: KoinAppDeclaration? = null)
fun koinConfigurationWithIndex(index: KoinIndex)
fun KoinApplication.withConfigurationWithIndex(index: KoinIndex)
```

### What's Untouched in koin-core

Everything else. `Module.mappings`, `module { }` DSL, `loadModules()`, `createDefinition()`, `buildSingle()`, `buildFactory()`, `ScopeDSL`, scope blocks — all remain unchanged. They are the `prebuiltIndex = false` path.

---

## What the Compiler Generates

### Annotation Path — `.module()` body

**Today (`prebuiltIndex = false`):**
```kotlin
fun AppModule.module(): Module = module {
    includes(DataModule().module())
    buildSingle(MyService::class, null) { scope, params -> MyService(scope.get()) }
    buildFactory(MyRepo::class, named("x")) { scope, params -> MyRepo(scope.get()) }
    scope<SessionScope> {
        buildScoped(SessionData::class, null) { scope, params -> SessionData(scope.get()) }
    }
}
```

**With `prebuiltIndex = true`:**
```kotlin
fun AppModule.module(): Module = indexedModule(
    buildKoinIndex {
        buildEntry(MyService::class, Kind.Singleton, null, rootScopeQualifier,
            listOf(IService::class), false) { scope, params -> MyService(scope.get()) }
        buildEntry(MyRepo::class, Kind.Factory, named("x"), rootScopeQualifier,
            emptyList(), false) { scope, params -> MyRepo(scope.get()) }
        buildEntry(SessionData::class, Kind.Scoped, null, typeQualifier<SessionScope>(),
            emptyList(), false) { scope, params -> SessionData(scope.get()) }
    }
)
```

Key differences:
- No `module { }` lambda — `indexedModule()` creates Module directly
- All definitions flat — scopes are a `scopeQualifier` parameter, not nested `scope<S> { }` blocks
- Bindings are a `secondaryTypes` parameter, not `.bind()` chains
- `buildEntry()` populates the HashMap directly — no intermediate storage
- Same definition lambda — `LambdaBuilder` output is identical

### DSL Path — `module { }` transformation

**User writes (unchanged):**
```kotlin
val myModule = module {
    single<MyService>()
    factory<MyRepo>()
}
```

**Today (`prebuiltIndex = false`):**
```kotlin
val myModule = module {
    buildSingle(MyService::class, null) { scope, params -> MyService(scope.get()) }
    buildFactory(MyRepo::class, null) { scope, params -> MyRepo(scope.get()) }
}
```

**With `prebuiltIndex = true`:**
```kotlin
val myModule = indexedModule(
    buildKoinIndex {
        buildEntry(MyService::class, Kind.Singleton, null, rootScopeQualifier,
            listOf(IService::class), false) { scope, params -> MyService(scope.get()) }
        buildEntry(MyRepo::class, Kind.Factory, null, rootScopeQualifier,
            emptyList(), false) { scope, params -> MyRepo(scope.get()) }
    }
)
```

**Both paths produce identical output** — same `indexedModule(buildKoinIndex { buildEntry(...) })` structure.

### `startKoin<T>()` transformation

**Today:**
```kotlin
startKoinWith(listOf(AppModule().module(), DataModule().module()), lambda)
```

**With `prebuiltIndex = true`:**
```kotlin
startKoinWithIndex(
    AppModule().module().index!! + DataModule().module().index!!,
    lambda
)
```

`!!` is safe — the compiler generated all `.module()` calls, so `.index` is guaranteed non-null.

### `modules()` call transformation (DSL path)

**User writes:**
```kotlin
startKoin {
    modules(myModule, otherModule)
}
```

**With `prebuiltIndex = true`, compiler transforms to:**
```kotlin
startKoin {
    modulesIndex(myModule.index!! + otherModule.index!!)
}
```

---

## Runtime: What Happens at Startup

### With `prebuiltIndex = false` (today)

```
module { } lambda executes
  → buildSingle() → createDefinition() → BeanDefinition → InstanceFactory → IndexKey → Module.mappings.put()
  → (repeated N times per module)
loadModules()
  → flatten(modules)                    // DFS over M modules
  → for each module:
  →   copy Module.mappings → _instances // N HashMap puts per module
```

**All N definitions processed at startup. Blocks app initialization.**

### With `prebuiltIndex = true`

```
At module init time (class loading, before startKoin):
  → buildKoinIndex { buildEntry(...) }
  → Each buildEntry(): BeanDefinition + InstanceFactory + IndexKey → HashMap.put
  → Result: KoinIndex with pre-populated HashMap

At startup (startKoin):
  → Merge indexes: index1 + index2 = combined HashMap (putAll)
  → loadIndex(): _instances.putAll(index.instances)
  → Done.
```

**At startup: one `putAll`. Everything else happened at class loading time.**

### Startup Cost Comparison

| Operation | Today (at startup) | Prebuilt Index (at startup) |
|-----------|--------------------|-----------------------------|
| Module lambda execution | M | 0 |
| `createDefinition()` dispatch | N | 0 |
| `BeanDefinition` allocation | N | 0 — class loading time |
| `InstanceFactory` allocation | N | 0 — class loading time |
| `IndexKey` computation | N | 0 — class loading time |
| `Module.mappings` HashMap alloc | M | 0 |
| `Module.mappings.put()` | N | 0 |
| Module DFS flattening | O(M) | 0 |
| `mappings → _instances` copy | N puts | 0 |
| **Actual startup work** | **~4N + O(M)** | **1 `putAll`** |

The work doesn't disappear — it moves from startup to module initialization (class loading time). Startup itself becomes O(1): assign the pre-populated map.

### Comparison with Dagger/Metro

| | Dagger/Metro | Koin + prebuiltIndex |
|---|---|---|
| Graph resolution | Compile time | Compile time |
| Factory creation | Compile time | Class loading time |
| Map/index building | Compile time | Class loading time |
| **Startup** | **Assign the graph** | **Assign the map** |
| Runtime resolution | Direct factory call | HashMap lookup (O(1)) → factory |

Same model. Koin keeps its dynamic `get<T>()` API with O(1) HashMap lookup — that's cheap and not the bottleneck. The bottleneck was building the map at startup, which is now eliminated.

---

## Unsafe DSL Detection

When `prebuiltIndex = true`, the compiler intercepts `module { }` blocks and examines every statement inside.

**Safe (typed DSL — compiler controls):**
- `single<T>()`, `factory<T>()`, `scoped<T>()`, `viewModel<T>()`, `worker<T>()`
- `scope<S> { scoped<T>() }` — scope blocks with typed definitions
- `includes(otherModule)` — preserved in `indexedModule(index, includes)`

**Unsafe (hand-written — compiler cannot prebuild):**
- `single { MyService(get()) }` — no type parameter, lambda is opaque
- Any other statement the compiler doesn't recognize

If ANY unsafe statement is detected → **compiler error**. The module cannot use the prebuilt index path. User must either convert to typed DSL or set `prebuiltIndex = false`.

---

## The Lambda

The definition lambda is the one piece that must remain runtime code:
```kotlin
{ scope: Scope, params: ParametersHolder -> MyService(scope.get(), scope.get(named("x"))) }
```

This is a `Function2<Scope, ParametersHolder, T>`. It does not capture anything from the `module { }` context — it is self-contained. `LambdaBuilder` already produces exactly this shape.

Today: lambda is passed to `buildSingle(kclass, qualifier, lambda)`.
With prebuilt index: same lambda is passed to `buildEntry(kclass, kind, qualifier, scopeQualifier, secondaryTypes, createdAtStart) { lambda }`.

**Same lambda, same `LambdaBuilder`, different call target. No changes to lambda generation.**

---

## `includes()` Handling

### Annotation path — no issue

The compiler discovers all modules at the `startKoin<T>()` site (explicit `@KoinApplication(modules = [...])` + `@Configuration` auto-discovery). Module graph is resolved at compile time. All indexes merged directly. No runtime `includes()` needed. No DFS.

### DSL path — preserved

```kotlin
val base = module { single<Repository>() }
val app = module {
    includes(base)
    single<Service>()
}
```

Compiler detects `includes(base)` inside the lambda and preserves it:

```kotlin
val app = indexedModule(
    buildKoinIndex {
        buildEntry(Service::class, Kind.Singleton, ...) { ... }
    },
    includes = listOf(base)
)
```

At runtime, included modules are still discovered via DFS — but each included module also has a prebuilt `.index`. The runtime flattening collects `.index` references and merges the pre-populated maps. No definition processing, no `Module.mappings` copying.

---

## Compiler Plugin Changes

### Gradle Plugin

- New `prebuiltIndex` option in `KoinGradleExtension` (default: `false`)
- Passed to compiler via command line option

### FIR Phase

**Unchanged.** Still generates `.module()` extension function stubs. Hint functions unchanged.

### IR Phase — New References

Resolve from classpath when `prebuiltIndex = true`:
- `KoinIndex`
- `buildKoinIndex`, `buildEntry`, `indexedModule`
- `Kind` enum values
- `startKoinWithIndex`, `koinApplicationWithIndex`, `koinConfigurationWithIndex`, `withConfigurationWithIndex`
- `modulesIndex`

**Fallback:** If not found on classpath (older koin-core) → ignore `prebuiltIndex` flag, use existing path. Log warning.

### IR Phase — `DefinitionCallBuilder`

New method `buildIndexEntry()`:
- Generates `buildEntry(kclass, kind, qualifier, scopeQualifier, secondaryTypes, createdAtStart) { lambda }`
- Same inputs as existing `buildClassDefinitionCall()` / `buildFunctionDefinitionCall()` / `buildTopLevelFunctionDefinitionCall()`
- Same `LambdaBuilder` for the definition lambda — **unchanged**
- Same `QualifierExtractor` for qualifiers — **unchanged**
- Simpler than existing code: no Module/ScopeDSL receiver, no `.bind()` chains, no scope block nesting

### IR Phase — `KoinAnnotationProcessor`

New method `buildModuleWithIndex()` — replaces `buildModuleCall()` when `prebuiltIndex = true`:
- Instead of `module { buildSingle(...); scope<S> { buildScoped(...) } }`
- Generates `indexedModule(buildKoinIndex { buildEntry(...); buildEntry(...) })`
- All definitions flat — scopes are a parameter, not nested blocks
- `ScopeBlockBuilder` not needed for this path

### IR Phase — `KoinDSLTransformer`

When `prebuiltIndex = true`:
- Walk the `module { }` lambda body
- For each `single<T>()` / `factory<T>()` / etc. → collect as `buildEntry()` data
- For any unrecognized statement → **emit compiler error**
- Replace entire `module { ... }` with `indexedModule(buildKoinIndex { buildEntry(...) })`
- Preserve `includes()` calls via `indexedModule(index, includes)` variant

When `prebuiltIndex = false`: **unchanged** — existing per-call `buildSingle()` transformation.

### IR Phase — `KoinStartTransformer`

When `prebuiltIndex = true`:
- `startKoin<T>()` → `startKoinWithIndex(M1().module().index!! + M2().module().index!!, lambda)`
- Same for `koinApplication<T>()`, `koinConfiguration<T>()`, `withConfiguration<T>()`
- `modules(m1, m2)` call → `modulesIndex(m1.index!! + m2.index!!)`

When `prebuiltIndex = false`: **unchanged**.

### Untouched Components

| Component | Why unchanged |
|-----------|---------------|
| `LambdaBuilder` | Same `{ scope, params -> T(scope.get()) }` lambda generation |
| `QualifierExtractor` | Same qualifier handling |
| `KoinArgumentGenerator` | Same `get()`, `getOrNull()`, `inject()` generation |
| Safety validation | Runs on same definition data before codegen |
| Hint functions | Same cross-module discovery |
| `ScopeBlockBuilder` | Still used when `prebuiltIndex = false` |
| Incremental compilation | Same tracking |

---

## Implementation Steps

Each step has a clear validation gate. Each step works independently — can stop at any step and have a working feature.

### Step 1: koin-core API

**Deliverable:** `KoinIndex` (with pre-populated HashMap), `buildKoinIndex()`, `KoinIndex.buildEntry()`, `indexedModule()`, `loadIndex()`, `startKoinWithIndex()`, `Module.index` field.

**Validation:** Unit test that creates a `KoinIndex` via `buildKoinIndex { buildEntry(...) }`, loads it via `loadIndex()`, resolves dependencies via `koin.get<T>()`. Proves the loading path works end-to-end before any compiler work.

### Step 2: Annotation path — `.module()` body

**Deliverable:** `KoinAnnotationProcessor` generates `indexedModule(buildKoinIndex { buildEntry(...) })` when `prebuiltIndex = true`.

**Validation:** Box test — `AppModule().module()` returns Module with `.index` containing correct entries. Existing tests still pass.

### Step 3: Annotation path — `startKoin` transformation

**Deliverable:** `KoinStartTransformer` generates `startKoinWithIndex(... .index!! + .index!!)` when `prebuiltIndex = true`.

**Validation:** Full end-to-end box test — `startKoin<MyApp> { }` loads via index path, `koin.get<MyService>()` resolves correctly.

### Step 4: DSL path

**Deliverable:** `KoinDSLTransformer` collects calls, detects unsafe, generates `indexedModule(buildKoinIndex { ... })`. `modules()` call transformed to `modulesIndex()`.

**Validation:** Box tests for DSL modules with prebuilt index. Unsafe detection test.

### Step 5: Gradle config + unsafe detection

**Deliverable:** `prebuiltIndex` option in `KoinGradleExtension`. Compiler error on unsafe DSL when enabled. Fallback when koin-core API not on classpath.

### Step 6: Validation

- Cross-module: module from dependency JAR with `.index`, consumed by app
- Incremental compilation: definition changes trigger recompilation correctly
- KMP: test on JVM, JS, Native
- Performance: benchmark startup time before/after

### Execution Order

```
Step 1: koin-core API + unit tests
    │
    ▼
Step 2: Annotation .module() body        Step 4: DSL path (can start after Step 2)
Step 3: Annotation startKoin             Step 5: Gradle config + unsafe detection
    │                                        │
    └────────────────┬───────────────────────┘
                     │
                     ▼
               Step 6: Validation
```

Steps 2+3 (annotation path) and Steps 4+5 (DSL path) can be developed in parallel after Step 1.

---

## Open Questions

### 1. `onClose` callbacks

Rarely used with compiler-generated definitions. Can add `callbacks` parameter to `buildEntry()` if needed later.

### 2. Index merging cost

`index1 + index2` creates a new `HashMap` and copies both maps. For M modules this is M `putAll` calls. Options:
- Accept it — `putAll` is optimized by the JVM, and M is typically small
- Compiler generates one flat `buildKoinIndex { all entries from all modules }` for same-compilation modules (avoids merge entirely)
- `KoinIndex` supports lazy merge: `plus` stores references, actual merge happens in `loadIndex()`