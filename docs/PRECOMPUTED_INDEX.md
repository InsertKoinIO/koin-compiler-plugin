# Precomputed Module Index

Design document for compile-time precomputation of Koin's instance registry entries via `KoinIndex`.

## Goal

The compiler already knows everything needed to populate `InstanceRegistry` — types, qualifiers, scopes, constructor calls, bindings. Today it generates `Module` DSL code that rebuilds this at runtime (N+1 imperative `buildSingle` / `buildFactory` calls per module, then copied into the registry). Instead, each module carries a precomputed `KoinIndex`, and at startup the compiler merges all indexes into one and loads it in a single pass.

## Two Usage Modes

| | Without Compiler Plugin | With Compiler Plugin |
|---|---|---|
| **Definitions** | `module { single { MyService(get()) } }` | `@Singleton class MyService(repo: Repo)` or `single<MyService>()` |
| **Modules** | `val m = module { ... }` | `@Module @ComponentScan class AppModule` or `module { single<T>() }` |
| **Startup** | `startKoin { modules(m1, m2) }` | `startKoin<MyApp> { }` or `startKoin { modules(m1, m2) }` |
| **Index** | None — `Module.mappings` built at runtime | `Module.index` precomputed by compiler, merged at startup |

Without the compiler plugin, there are no annotations, no `single<T>()` — just hand-written Module DSL. That path stays unchanged.

With the compiler plugin, each `Module` carries a precomputed `.index`. At startup, the compiler transforms `modules(m1, m2, m3)` into `modulesIndex(m1.index + m2.index + m3.index)` — one merged index, one `loadIndex()` call.

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

### What happens today at runtime

Both annotation-based (`.module()`) and DSL (`single<T>()`) paths converge on `createDefinition()` in `ModuleExt.kt`. Each call:

1. Creates `BeanDefinition<T>(scopeQualifier, primaryType, qualifier, definition, kind, secondaryTypes)`
2. Wraps in `InstanceFactory` (`SingleInstanceFactory` / `FactoryInstanceFactory` / `ScopedInstanceFactory`)
3. Computes `IndexKey` string via `indexKey("type:qualifier:scope")`
4. Stores in `Module.mappings: LinkedHashMap<IndexKey, InstanceFactory<*>>`

Then `InstanceRegistry.loadModules()` iterates M modules, copying each module's N mappings into `_instances`.

Total cost: **M × N imperative definition calls** + **M × N map insertions into Module.mappings** + **M × N map copies into _instances** = effectively 3 × M × N operations.

### The convergence point: createDefinition()

```kotlin
// ModuleExt.kt — both annotation and DSL paths call this
fun <T : Any> createDefinition(
    kclass: KClass<T>,
    definition: Definition<T>,
    qualifier: Qualifier?,
    scopeQualifier: Qualifier,
    factoryKind: Kind,
    module: Module,
    createdAtStart: Boolean
): KoinDefinition<T>
```

These inputs are exactly what the compiler knows at compile time. This is what `IndexEntry` captures.

## What the Compiler Already Knows

At compile time, for every definition (annotation-based or DSL), the plugin has:

| Information | Source | Example |
|------------|--------|---------|
| Primary type | Type parameter or return type | `MyService::class` |
| Kind | DSL function or annotation | `Singleton`, `Factory`, `Scoped` |
| Qualifier | `@Named("x")`, `@Qualifier` | `named("http")` |
| Scope qualifier | `@Scope(MyScope::class)` or DSL context | `typeQualifier<MyScope>()` |
| Constructor call | Primary constructor analysis | `MyService(get(), get(named("x")))` |
| Secondary types (bindings) | Supertype analysis | `listOf(IService::class)` |
| Eager flag | `createdAtStart` parameter | `true` / `false` |

None of this needs to be computed at runtime.

## Proposed Design

### Overview

Two layers of optimization:

1. **Per-module**: Each `Module` carries a precomputed `KoinIndex` (filled by compiler). No imperative DSL evaluation needed.
2. **At startup**: The compiler transforms `modules(m1, m2, m3)` → `modulesIndex(m1.index + m2.index + m3.index)`. All indexes merged into one, loaded in a single `loadIndex()` call.

This eliminates both the per-module N+1 DSL evaluation AND the per-module loading iteration.

### Step 1: Module gains `.index`

```kotlin
// In koin-core — Module class (existing, extended)
class Module {
    // ... existing fields (mappings, scopes, includedModules, eagerInstances) ...

    // NEW: precomputed index, filled by compiler plugin
    var index: KoinIndex? = null
}
```

### Step 2: Compiler fills `.index` on each module

**Annotation path:**

```kotlin
// Today — compiler generates:
fun AppModule.module(): Module = module {
    buildSingle(MyService::class, null) { MyService(get()) }
    buildFactory(MyRepo::class, named("x")) { MyRepo(get()) }
    includes(DataModule().module())
}

// New — compiler generates:
fun AppModule.module(): Module = module { }.also { m ->
    m.index = koinIndex(
        indexEntry(MyService::class, Singleton, null, rootScope, listOf(IService::class)) { MyService(get()) },
        indexEntry(MyRepo::class, Factory, named("x"), rootScope, emptyList()) { MyRepo(get()) },
    )
}
```

**DSL path:**

```kotlin
// User writes (unchanged):
val myModule = module {
    single<MyService>()
    factory<MyRepo>()
}

// Compiler transforms to:
val myModule = module { }.also { m ->
    m.index = koinIndex(
        indexEntry(MyService::class, Singleton, null, rootScope, ...) { MyService(get()) },
        indexEntry(MyRepo::class, Factory, null, rootScope, ...) { MyRepo(get()) },
    )
}
```

Both paths: `module { }` returns `Module`, compiler attaches `.index`. Same user code, same types, same imports.

### Step 3: Compiler transforms `modules()` to merge indexes at startup

The key optimization. The compiler sees the `modules(m1, m2, m3)` call and transforms it to aggregate all indexes into one merged `KoinIndex`, loaded in a single pass.

**Annotation path (`startKoin<T>()`):**

```kotlin
// User writes:
startKoin<MyApp> { }

// Today — compiler generates:
startKoinWith(listOf(AppModule().module(), DataModule().module()), lambda)

// New — compiler generates:
startKoinWithIndex(AppModule().module().index + DataModule().module().index, lambda)
// One merged KoinIndex, one loadIndex() call
```

**DSL path (`startKoin { modules(...) }`):**

```kotlin
// User writes:
startKoin {
    modules(myModule, otherModule, thirdModule)
}

// Compiler transforms modules() call to:
startKoin {
    modulesIndex(myModule.index + otherModule.index + thirdModule.index)
}
// One merged KoinIndex, one loadIndex() call
```

Both paths converge on: **one `KoinIndex`, one `loadIndex()` call, one iteration over all entries.**

### Step 4: Runtime loads merged index

```kotlin
// New koin-core API

// KoinIndex merging via + operator
operator fun KoinIndex.plus(other: KoinIndex): KoinIndex = KoinIndex(
    entries = this.entries + other.entries,
    scopes = this.scopes + other.scopes
)

// New loading function on KoinApplication
fun KoinApplication.modulesIndex(index: KoinIndex) {
    koin.instanceRegistry.loadIndex(index, allowOverride)
    koin.scopeRegistry.loadScopes(index.scopes)
}

// New startup function for annotation path
fun startKoinWithIndex(index: KoinIndex, config: KoinAppDeclaration? = null)

// Single-pass index loading in InstanceRegistry
fun InstanceRegistry.loadIndex(index: KoinIndex, allowOverride: Boolean) {
    for (entry in index.entries) {
        val def = BeanDefinition(
            entry.scopeQualifier, entry.kclass, entry.qualifier,
            entry.definition, entry.kind, entry.secondaryTypes
        )
        val factory = when (entry.kind) {
            Kind.Singleton -> SingleInstanceFactory(def)
            Kind.Factory -> FactoryInstanceFactory(def)
            Kind.Scoped -> ScopedInstanceFactory(def)
        }
        // Index primary type
        saveMapping(allowOverride, indexKey(entry.kclass, entry.qualifier, entry.scopeQualifier), factory)
        // Index secondary types (bindings)
        for (secondaryType in entry.secondaryTypes) {
            saveMapping(allowOverride, indexKey(secondaryType, entry.qualifier, entry.scopeQualifier), factory)
        }
        // Track eager instances
        if (entry.createdAtStart && factory is SingleInstanceFactory<*>) {
            eagerInstances[factory.hashCode()] = factory
        }
    }
}
```

### What this eliminates

| Cost | Before | After |
|------|--------|-------|
| M × N `buildSingle()` / `buildFactory()` calls | Yes — each creates BeanDef + Factory + IndexKey | No — skipped entirely |
| M × N insertions into `Module.mappings` | Yes | No — no mappings populated |
| M module iterations in `loadModules()` | Yes | No — one `loadIndex()` call |
| Module flattening (DFS) | Yes | No — includes resolved at compile time, indexes merged by compiler |
| Map copy (Module.mappings → _instances) | Yes — double mapping | No — direct to `_instances` |
| BeanDefinition + InstanceFactory creation | M × N at DSL time | Total N at load time (one pass) |
| IndexKey computation | M × N at DSL time | Total N at load time (one pass) |

**From 3 × M × N operations to 1 × N.** One iteration over all entries, straight into `_instances`.

## New koin-core API

```kotlin
/**
 * A single precomputed definition entry.
 * Captures the same inputs as createDefinition() — everything the compiler knows at compile time.
 */
class IndexEntry<T : Any>(
    val kclass: KClass<T>,
    val kind: Kind,                          // Singleton | Factory | Scoped
    val qualifier: Qualifier?,
    val scopeQualifier: Qualifier,
    val definition: Definition<T>,           // Scope.(ParametersHolder) -> T
    val secondaryTypes: List<KClass<*>>,     // interface bindings
    val createdAtStart: Boolean = false
)

/**
 * A precomputed module index — flat list of all definition entries.
 * Attached to Module by the compiler plugin. Merged at startup for single-pass loading.
 */
class KoinIndex(
    val entries: List<IndexEntry<*>>,
    val scopes: Set<Qualifier> = emptySet()
) {
    operator fun plus(other: KoinIndex): KoinIndex = KoinIndex(
        entries = this.entries + other.entries,
        scopes = this.scopes + other.scopes
    )
}

// Builder functions (called by compiler-generated code)
fun koinIndex(vararg entries: IndexEntry<*>): KoinIndex

fun <T : Any> indexEntry(
    kclass: KClass<T>,
    kind: Kind,
    qualifier: Qualifier?,
    scopeQualifier: Qualifier,
    secondaryTypes: List<KClass<*>> = emptyList(),
    createdAtStart: Boolean = false,
    definition: Definition<T>
): IndexEntry<T>
```

**Changes to existing types:**

```kotlin
// Module class — one new field
class Module {
    // ... all existing fields unchanged ...
    var index: KoinIndex? = null  // NEW — filled by compiler plugin
}

// InstanceRegistry — one new method
class InstanceRegistry {
    // ... existing loadModules unchanged, still works for hand-written DSL ...
    fun loadIndex(index: KoinIndex, allowOverride: Boolean)  // NEW
}

// KoinApplication — one new method
class KoinApplication {
    // ... existing modules() unchanged ...
    fun modulesIndex(index: KoinIndex)  // NEW — called by compiler-transformed code
}
```

## Key Decisions

### 1. Per-module index + merged at startup ✅

Each module carries its own `KoinIndex` (per-module precomputation). At startup, the compiler transforms `modules(m1, m2, m3)` to merge all indexes into one via `+` operator, loaded in a single `loadIndex()` call. No per-module iteration at runtime.

### 2. Unified output for annotations and DSL ✅

Both produce `IndexEntry` with the same fields. The compiler generates the same `koinIndex(indexEntry(...), ...)` regardless of whether the source is `@Singleton class MyService` or `single<MyService>()`.

### 3. Zero API breakage ✅

- `Module` class unchanged (one new optional field)
- `module { }` still returns `Module`
- `startKoin { modules(...) }` still works — compiler transforms the `modules()` call
- User code, imports, types — all unchanged
- Existing stubs (`Module.single<T>()`, etc.) unchanged
- Hand-written DSL without compiler works as before (`.index` is null → existing path)

### 4. IndexKey: runtime call with constants ✅

Generate `indexKey(MyService::class, null, rootQualifier)` — not precomputed strings. `KClass.getFullName()` varies across platforms. Trivial runtime cost, guaranteed correctness.

### 5. Fallback for non-compiler modules ✅

If `.index` is null (hand-written module without compiler), the existing `loadModules()` path handles it. Both paths feed the same `InstanceRegistry._instances` map. Mixing is possible but not the intended use case — users pick one mode.

## Impact on Compiler Plugin Phases

### FIR Phase — Unchanged
- Still generates `.module()` extension function declarations
- Hint functions unchanged

### IR Phase — Modified
- Phase 1 (annotations): Unchanged — collects definitions
- Phase 2 (DSL): `single<T>()` still intercepted, now produces `indexEntry()` data for `.index` instead of `buildSingle()` call
- Phase 1b: `.module()` body generation changes — builds empty `module { }` + attaches `.index` with all definitions
- Phase 3 (startKoin):
  - **Annotation path**: Generates `startKoinWithIndex(M1().module().index + M2().module().index, lambda)` instead of `startKoinWith(listOf(M1().module(), M2().module()), lambda)`
  - **DSL path**: Transforms `modules(m1, m2)` → `modulesIndex(m1.index + m2.index)`
- Safety validation: Unchanged — same definition data

### Cross-module — Unchanged
- Hint functions still used for cross-module discovery
- Each module's `.module()` carries its `.index`
- Cross-module indexes merged at the `startKoin` / `modules()` call site

## Performance Expectations

| Metric | Current | With KoinIndex |
|--------|---------|----------------|
| Module DSL evaluation | M × N `buildSingle`/`buildFactory` calls | 0 — skipped |
| Module.mappings population | M × N insertions | 0 — skipped |
| Module flattening (DFS) | O(M) | 0 — compile-time |
| Per-module loading iteration | M iterations | 0 — one merged index |
| Map copy (Module.mappings → _instances) | M × N entries | 0 — direct to _instances |
| BeanDefinition + InstanceFactory creation | M × N at DSL time | Total N at load time (one pass) |
| IndexKey computation | M × N at DSL time | Total N at load time (one pass) |

**From O(M × N) × 3 passes to O(N) × 1 pass.**

## Future Optimizations (V2/V3)

Once V1 is stable, further optimizations become possible:

1. **Eliminate `Module` wrapper entirely** — For annotation path, `.index()` returns `KoinIndex` directly. No `Module` allocation. DSL path follows later.

2. **Eliminate `BeanDefinition`** — Make `InstanceFactory` work directly with `IndexEntry`. Saves N allocations at load time. Needs lightweight alternative for error messages/logging.

3. **Precompute `IndexKey` strings** — Once platform behavior of `KClass.getFullName()` is verified stable, generate `IndexKey` strings at compile time. Zero runtime computation.

4. **Pre-create `InstanceFactory` instances** — Move factory creation from load time to compile time (generated as static instances). Saves N allocations at load time.

## Implementation Plan

### Phase A: koin-core API (do first — compiler plugin depends on it)

Everything else blocks on these types existing in koin-core.

**A1. New types**
- Create `IndexEntry<T>` class — `kclass`, `kind`, `qualifier`, `scopeQualifier`, `definition`, `secondaryTypes`, `createdAtStart`
- Create `KoinIndex` class — `entries: List<IndexEntry<*>>`, `scopes: Set<Qualifier>`, `plus` operator
- Create builder functions: `koinIndex(vararg entries)`, `indexEntry(...)`
- Package: `org.koin.core.module` (alongside `Module`) or new `org.koin.core.index`

**A2. Module extension**
- Add `var index: KoinIndex? = null` field to `Module` class

**A3. InstanceRegistry.loadIndex()**
- New method: single-pass loading from `KoinIndex`
- Handles: `BeanDefinition` creation, `InstanceFactory` wrapping, primary + secondary type indexing, eager instances tracking

**A4. ScopeRegistry support**
- Accept `Set<Qualifier>` from `KoinIndex.scopes` (reuse existing `loadScopes` or add overload)

**A5. KoinApplication.modulesIndex()**
- New method: takes `KoinIndex`, calls `instanceRegistry.loadIndex()` + scope registration
- Called by compiler-transformed `modules()` calls

**A6. startKoinWithIndex()**
- New startup function in `org.koin.plugin.module.dsl` (alongside existing `startKoinWith`)
- Takes `KoinIndex` + config lambda
- Called by compiler-transformed `startKoin<T>()` calls

**A7. Unit tests**
- `IndexEntry` creation and field correctness
- `KoinIndex` merging via `+` operator
- `loadIndex()` populates `_instances` correctly: primary types, secondary types (bindings), qualifiers, scopes, eager instances
- Override behavior with index entries
- Fallback: `Module` with `index = null` still loads via existing `loadModules()` path
- Mixed scenario: some modules with `.index`, some without

---

### Phase B: Compiler plugin — annotation path

**B1. IR references to new koin-core API**
- Add `context.referenceClass()` / `context.referenceFunctions()` lookups for `KoinIndex`, `IndexEntry`, `koinIndex`, `indexEntry`, `Module.index` setter, `startKoinWithIndex`
- Add fallback: if new API not found on classpath (older koin-core), fall back to existing `.module()` generation

**B2. Generate `.index` attachment in `.module()` body**
- Modify `KoinAnnotationProcessor.fillFunctionBody()`:
  - Instead of `module { buildSingle(...); buildFactory(...); includes(...) }`
  - Generate `module { }.also { it.index = koinIndex(indexEntry(...), indexEntry(...), ...) }`
- For each `Definition.ClassDef`, `Definition.FunctionDef`, `Definition.TopLevelFunctionDef`:
  - Emit `indexEntry(kclass, kind, qualifier, scopeQualifier, secondaryTypes, createdAtStart, definitionLambda)`
  - Reuse existing `LambdaBuilder` for the definition lambda (same constructor call generation)
  - Reuse existing `QualifierExtractor` for qualifier arguments

**B3. Transform `startKoin<T>()` to use merged index**
- Modify `KoinStartTransformer`:
  - Instead of `startKoinWith(listOf(M1().module(), M2().module()), lambda)`
  - Generate `startKoinWithIndex(M1().module().index!! + M2().module().index!!, lambda)`
  - Or generate one flat `koinIndex(e1, e2, ..., eN)` inline with all entries from all discovered modules (avoids intermediate `+` allocations)

**B4. Tests**
- Update existing annotation box tests — verify runtime behavior unchanged (get/inject still work)
- New box test: `startKoin<T>()` loads via index path, resolves dependencies correctly
- Verify `.index` is populated on generated modules
- Update golden files (`.fir.ir.txt`) to reflect new IR output
- Cross-module test: module from dependency JAR with `.index`, consumed by app module

---

### Phase C: Compiler plugin — DSL path

**C1. Transform `module { single<T>(); factory<T>() }` to attach `.index`**
- Modify `KoinDSLTransformer`:
  - Collect all `indexEntry()` data from intercepted `single<T>()`, `factory<T>()`, `scoped<T>()` calls within a `module { }` block
  - Instead of transforming each call to `buildSingle()` on `Module` receiver, generate empty `module { }` + `.also { it.index = koinIndex(...) }` with all collected entries
  - Scope blocks: `scope<MyScope> { scoped<T>() }` → scope qualifier collected into `KoinIndex.scopes`, entries get `scopeQualifier` set accordingly

**C2. Transform `modules(m1, m2, m3)` call inside `startKoin { }`**
- Detect `modules()` calls inside `startKoin { }` lambda (or `KoinApplication.modules()`)
- Transform to `modulesIndex(m1.index!! + m2.index!! + m3.index!!)`
- Or generate one flat merged `koinIndex(...)` inline

**C3. Tests**
- Update existing DSL box tests (single_basic, factory_basic, etc.)
- New test: `module { single<T>() }` with `.index` populated, loaded via `modulesIndex()`
- Scope test: `module { scope<S> { scoped<T>() } }` produces correct scope entries in index
- Verify `create(::T)` path also produces index entries

---

### Phase D: Validation & cleanup

**D1. Safety validation**
- Verify compile-time safety (A1-A4, B, C validation phases) still runs correctly
- Safety validation operates on the same definition data before index generation — should be unaffected
- Run full diagnostic test suite

**D2. Cross-module**
- Verify hint functions still work (orthogonal to index generation)
- Cross-module `.module()` calls from dependencies carry `.index`
- Merged index at `startKoin` site includes cross-module entries

**D3. Incremental compilation**
- Verify IC tracking still works (`lookupTracker`, `expectActualTracker`)
- `.index` content changes when definitions change → module recompilation triggered correctly
- Adding/removing a definition in one module triggers recompilation of `startKoin` site

**D4. KMP**
- Test on JVM, JS, Native targets
- Verify `indexKey()` with `KClass` works correctly on all platforms
- Verify `KoinIndex` serialization in klib metadata (for cross-module)

**D5. Performance benchmarking**
- Measure startup time before/after on sample app (`test-apps/sample-app`)
- Compare: N definitions across M modules — measure `loadModules()` vs `loadIndex()` time
- Validate the O(M × N) → O(N) improvement

---

### Execution Order & Dependencies

```
Phase A: koin-core API
  A1 → A2 → A3 → A4 → A5 → A6 → A7
  │
  │  Release koin-core with KoinIndex API
  │
  ▼
Phase B: Compiler — annotations          Phase C: Compiler — DSL
  B1 (IR references)                       (depends on B1)
  B2 (generate .index)                     C1 (transform module{} block)
  B3 (transform startKoin<T>)              C2 (transform modules() call)
  B4 (tests)                               C3 (tests)
  │                                        │
  └────────────────┬───────────────────────┘
                   │
                   ▼
             Phase D: Validation
               D1 (safety)
               D2 (cross-module)
               D3 (incremental compilation)
               D4 (KMP)
               D5 (benchmarks)
```

Phase A is the blocker. B and C can be developed in parallel once A is released. D is the final validation pass.

## Known Issues & Notes

### 1. `includes()` lost when emptying `module { }` body ⚠️

When the compiler generates `module { }.also { it.index = ... }`, any `includes()` calls in the original body are lost:

```kotlin
val base = module { single<Repository>() }
val app = module { includes(base); single<Service>() }
startKoin { modules(app) }  // only app is listed — base never loaded!
```

**For the annotation path** this is not an issue — the compiler discovers all modules (explicit + `@Configuration` + includes) at the `startKoin<T>()` site and generates the full merged index.

**For the DSL path** this needs to be handled. Options:
- (a) Keep `includes()` calls in the `module { }` body, only skip definition calls. The `Module` still carries includes for runtime flattening, but definitions come from `.index`.
- (b) Compiler resolves `includes()` at compile time: detect `includes(base)` in the lambda, track `base` as a dependency, and add it to the `modulesIndex()` merge at the `modules()` call site.
- (c) `KoinIndex` gains an `includes: List<KoinIndex>` field for nested composition.

Option (a) is simplest for V1 — keep `includes` working via the existing `Module` path, only optimize definition loading via `.index`. Module flattening still happens but is cheap.

### 2. Null safety on `.index` access ⚠️

Generated code like `m1.index!! + m2.index!!` crashes if any module has `.index = null` (e.g., from a dependency compiled without the plugin, or an older koin-core).

**Mitigation**: The compiler should generate safe fallback logic:
- For `startKoin<T>()`: the compiler generates all `.module()` calls itself, so `.index` is guaranteed non-null. `!!` is safe here.
- For `modules(m1, m2)` transformation: the compiler can only guarantee `.index` for modules it transforms in the current compilation. For external modules, generate a fallback that checks `.index` and falls back to `loadModule()` if null:

```kotlin
// Instead of: modulesIndex(m1.index!! + m2.index!!)
// Generate:
if (m1.index != null && m2.index != null) {
    modulesIndex(m1.index!! + m2.index!!)
} else {
    modules(m1, m2)  // existing path
}
```

Or simpler: only transform `modules()` calls where all module variables are locally defined (compiler knows it set `.index` on them).

### 3. Empty `module { }` allocation overhead ⚠️

`module { }` still allocates a `Module` object with `LinkedHashMap`, `LinkedHashSet`, etc. — all unused when `.index` carries the definitions.

**V1 mitigation**: Use `Module()` constructor directly instead of `module { }` DSL function. Avoids the lambda allocation and DSL setup.

```kotlin
// Instead of: module { }.also { it.index = ... }
// Generate: Module().also { it.index = ... }
```

**V2+**: Eliminate `Module` wrapper entirely — generate `KoinIndex` directly.

### 4. Performance claim precision 📝

The "3 × M × N" framing in the doc assumes each of M modules has N definitions. More precisely:

- **N_total** = total definitions across all M modules
- **Today**: N_total `createDefinition()` calls + N_total `Module.mappings` insertions + N_total copies to `_instances` + O(M) flattening = **~3 × N_total**
- **With index**: N_total `BeanDefinition` + `InstanceFactory` allocations + N_total `saveMapping` calls = **~2 × N_total**

The win is **3 × N_total → 2 × N_total**, plus eliminating DSL call dispatch overhead, Module allocation, and the O(M) flattening pass. The real-world improvement depends on N_total and the per-call overhead of `createDefinition()` vs direct `saveMapping()`.

## Open Questions

1. **`@Monitor` interaction** — Monitor wrapping happens at the InstanceFactory level. `loadIndex()` needs to apply monitoring when configured.

2. **`onClose` callbacks** — Rarely used with compiler-generated definitions. Can add `callbacks` field to `IndexEntry` if needed.

3. **Koin version alignment** — `KoinIndex` + `IndexEntry` + `Module.index` must exist in koin-core before the compiler plugin can target them. Coordinate koin-core release first.

4. **Index merging cost** — `index1 + index2 + index3` creates intermediate lists. Could use `buildList { addAll(i1.entries); addAll(i2.entries); ... }` for a single allocation. Or the compiler generates one flat `koinIndex(e1, e2, ..., eN)` with all entries inline.
