# Compile Safety — A3 Reshape Plan

> Status: design, pre-implementation (2026-07-22). Supersedes the "A2 relax-all" idea.
> Grounding: three code audits (metadata carrier, verify logic, entry-point/module-graph),
> a comparable-K2-DI-plugin carrier study, and an A2 collect-only spike that **falsified** the blunt version.

## 1. Principle

**A2 (per-module) does NO graph-resolution validation. A3 (entry point) is the sole graph verifier.**

Nothing about *binding resolution* is decidable inside a non-entry module: adding a module can add or
override providers, so a locally-"missing", wrong-qualifier, or wrong-scope binding may resolve fine once
the graph is assembled at the root. A2 hard-erroring these is the false-positive engine that hurts real
multi-module apps.

- **A2 keeps only structural/shape checks that never touch the graph:** call-site `parametersOf` arity
  (KOIN-D005/D006), DSL `create()`-is-only-instruction (`unsafeDslChecks`), malformed annotations.
- **A2 collects maximally** (over-collect metadata; log everything discoverable) and defers every
  graph-resolution question to A3. Collection never errors.
- **A3 assembles the full loaded graph at the root and is the only place `KOIN-D001` (missing) /
  qualifier / scope / cycle diagnostics fire.**

### The danger (doctrine: "silent is worse than broken")

The moment A2 stops erroring, the worst failure class — a *silent* missing dependency → runtime crash —
is in play. So three **hard gates** must land *before* A2's graph validation is removed. Removing it early
just swaps loud false positives for silent false negatives.

- **Gate 1 — every ROOT entry point verifies authoritatively, and A3 actually EMITS** (today roots
  don't all verify and A3 emits nothing user-visible — see §2 and §4b).
- **Gate 2 — the carrier carries provider REQUIREMENTS**, including function providers
  (today `ExternalFunctionDef → emptyList()`), so A3 never under-checks (see §3).
- **Gate 3 — freshness**: A3's metadata is stale-proof across incremental builds (see §3b). A2 validates
  per-module *at that module's own compile*, so it is freshness-robust by construction; A3-as-sole-verifier
  removes that property and concentrates all correctness into one compile that reads every module's
  metadata. If freshness fails, the failure is a silent false negative on the exact incremental multi-module
  loop the reshape exists to serve.

## 2. Entry-point catalog (the "safe set")

An entry point authoritatively verifies only if BOTH: (a) its loaded module set is statically resolvable,
and (b) it is the **terminal root**, not a fragment composed into a larger root.

### Roles

| Role | Meaning |
|---|---|
| **Root** | Assembles + owns the complete module set. Must verify. |
| **Fragment** | A config piece composed into a root (`koinConfiguration` consumed by `withConfiguration` / Compose `KoinApplication(configuration=…)`). Verifying it in isolation = false positives. Must be aggregated into its root, not verified alone. |
| **Loader** | `module<T>()` / `modules(vararg)` — contributes definitions to whichever root; never a root itself. |
| **Dynamic** | Module set not statically enumerable (`modules(if(debug)…)`, spread of a runtime list). Unverifiable — must DISCLOSE, never silently pass. |

### Current state vs target

| Form | FqName | Role | Verifies today? |
|---|---|---|---|
| `startKoin<T>()` + `@KoinApplication(modules=[…])` | `org.koin.plugin.module.dsl.startKoin` (typed) | Root | ✅ `validateFullGraph` |
| `startKoin { modules(…) }` (plugin stub, untyped) | `…plugin.module.dsl.startKoin` | Root | ⚠️ only if `modules(…)` statically found, else silent skip |
| `koinApplication { modules(…) }` (stub) | `…plugin.module.dsl.koinApplication` | Root | ⚠️ same guard |
| `koinConfiguration { modules(…) }` (stub) | `…plugin.module.dsl.koinConfiguration` | Root or Fragment | ⚠️ same guard; NOT classified root vs fragment |
| `withConfiguration { modules(…) }` (stub) | `…plugin.module.dsl.withConfiguration` | Composition | ⚠️ validated in isolation; aggregation onto base root unconfirmed |
| **`startKoin { modules(…) }` (real koin-core)** | `org.koin.core.context.startKoin` | **Root** | ❌ flag only → Phase 3.1 DSL-only path (spike: doesn't settle) |
| `GlobalContext.startKoin` | `org.koin.core.context.GlobalContext.startKoin` | Root | ❌ flag only |
| `KoinApplication.Companion.init` | same | Root (low-level) | ❌ flag only |
| **`koinConfiguration { }` (real, Compose)** | `org.koin.dsl.koinConfiguration` | Root or Fragment | ❌ flag only → Phase 3.1 (spike: doesn't settle deferrals) |
| **Compose `KoinApplication(configuration=…) { }`** | koin-compose composable | Root | ❌ recognized only via its `koinConfiguration` arg → same gap |
| `module<T>()` | `…plugin.module.dsl.module` | Loader | n/a (contributes) |
| `modules(vararg KClass)` | `…plugin.module.dsl.modules` | Loader | n/a (contributes) |

### Missing safe entry points (the gap to close in Gate 1)

1. **Ordinary `startKoin { modules(appModule) }` (koin-core)** — the most common form — is only on the
   Phase 3.1 DSL path, not authoritative A3.
2. **Standalone `koinConfiguration { }` / Compose `KoinApplication()`** — Compose apps; Phase 3.1 only,
   spike showed it doesn't settle deferrals.
3. **`withConfiguration` aggregation** — a base root + `withConfiguration` additions form ONE graph; the
   verifier must aggregate them, not check the addition in isolation.
4. **Any root whose `modules(…)` isn't statically found** — currently silently skipped
   (`isNotEmpty()` guards). Under A3-sole-verifier this becomes a silent hole → must DISCLOSE.

### Dynamic dependencies → `@Provided`

Where a dependency is supplied at runtime (not in the compile-time graph), `@Provided` is the escape
hatch — the verifier trusts it and skips it. This is orthogonal to a dynamic *module set*: `@Provided`
covers unknown *dependencies*; an unknowable *set of definitions* (dynamic `modules(…)`) is the "Dynamic"
role above and gets a disclosure warning, not `@Provided`.

## 3. Carrier (log everything, portably)

Resource files are **dead on KMP** (klib `resources/` "not used yet"; no portable compile-time read).
The reference-plugin model = hint (discovery) + `@Metadata` proto (detail) + **requirements carried as real typed
Kotlin declarations, not strings**.

- **Now:** move to an **annotation-argument carrier** (proven portable via kotlinx.serialization
  `@SerialInfo`; low API risk; kills today's name-mangling). Carry per-provider: def type, provided/bound
  types, qualifiers, scope, **requirement list (incl. function providers)**, and **source origin
  (module FqName + file/line)** so A3 diagnostics point at the culprit, not the aggregator.
- **Later (behind a native+wasm gate):** `@Metadata`-on-owned-declaration — its payoff is IC freshness
  (cures the orphan-hint false-green — the DSL orphan-hint IC bug). Higher API risk (the reference plugin hit a non-JVM
  deserialization bug).
- **Cheap wins the reference plugin proves regardless of carrier:** the `Any?` hint-type degradation may be unnecessary
  (`@Deprecated(HIDDEN)` alone survives native); wire BOTH `LookupTracker` + `ExpectActualTracker` and
  link each hint → source class to force recompile/removal on change.

### Decision: typed-mirror + annotation-arg, NO protobuf, NO `@Metadata` blob

Do NOT serialize the graph. The dependency chain is never a stored artifact — each provider exposes its
own edges and A3 (the linker) reconstructs the chain by type-matching. (The reference plugin's protobuf carries only a
provider *index*; its dependency edges ride on typed declarations, not the proto.)

- **Requirements → a generated typed mirror declaration** whose parameter *types* + per-param annotations
  ARE the requirement list (nullable→getOrNull, `@Named`→qualifier, `@InjectedParam`/`@Provided`→skip).
  Compiler-checked, KMP-portable, no serialization library. Closes the function-provider gap
  (`ExternalFunctionDef → emptyList()`); class providers already expose edges via constructor ABI.
- **Origin (module FqName + file/line) → annotation argument** (small string data).
- **Channel stays the existing generated-declaration hints package.** No protobuf, no
  `@Metadata` custom-blob. The `@Metadata` route is a SEPARATE 1.1+ upgrade whose only payoff is IC
  freshness (orphan-hint cure), high API risk, gated behind a native/wasm test — never for carrying the graph.

## 3b. Gate 3 — freshness (incremental compilation)

A3-as-sole-verifier is correct only if, on an incremental build, (a) every changed module's metadata on
disk is fresh and (b) the aggregator's compile actually re-runs to re-read it. Three sub-problems, each
with a lever:

1. **Aggregator doesn't re-run** — IC marks the entry-point `compileKotlin` UP-TO-DATE though the graph
   changed. Lever: `strictSafety`, today an opt-in auto-detect, becomes **MANDATORY whenever an entry
   point is present** (A3 is now the only safety pass). Cost stays bounded — only the aggregator recompiles.
2. **Changed/removed def leaves stale metadata** — the DSL orphan-hint bug: a removed def leaves a hint
   class IC never GCs, so A3 sees a provider that no longer exists. Lever: wire **both** `LookupTracker`
   *and* `ExpectActualTracker`, and **link each generated hint back to its source class** so a
   change/removal forces the hint to recompile or vanish. (Partially wired today.)
3. **Newly-introduced file nothing referenced before** — `@ComponentScan` adds a `@Singleton class` to a
   scanned package with no source edge pointing at it. Hard residual K2 limit. Lever: strictSafety re-run
   + package-scope lookup tracking; beyond that, a limit to **disclose**, not silently miss.

**Exit test — an incremental stress matrix in `playground-apps`** (real Gradle modules + IC), generalizing
the existing DSL orphan stress test. For each of {add a def, **remove** a def, change a qualifier, add a
scanned class} in a dependency module, rebuild **without a clean**, and assert A3 reacts correctly (catches
the new miss / clears the resolved one / does not false-green on the removal). A green clean-build proves
nothing here — the diagnostics harness is single-compilation and cannot see IC.

**Decision:** lean on the IC-tracker + mandatory-strictSafety levers, not a bespoke content-hash dirty
tracker (which fights Gradle's own task-input tracking and the K2 IC grain).

## 4. Work breakdown

Each step lands with box tests (RED before GREEN) + native `iosArm64`/`wasmJs` compile + the playground
compile-safety stress test. Gate 1 = Steps 2+3, Gate 2 = Step 4, Gate 3 = Step 4c; **Step 5 (demote A2)
is blocked on all three gates.**

- **Step 1 — Baseline truth (do first).** Box-test matrix: for EACH form in §2, assert what fires today
  (D001 / W002 / nothing) for (a) a genuine missing dep and (b) a valid cross-module graph. This turns the
  "⚠️/❌ unconfirmed" cells into evidence and is the RED baseline for the reshape.
- **Step 2 — Reify EntryPoint + classifier.** Replace the `hasKoinEntryPoint` boolean with an EntryPoint
  model carrying `(kind, appClass?, resolvedModules, isRoot, isDynamic)`. Classify root vs fragment vs
  loader vs dynamic (fragment = its result flows into `withConfiguration` / Compose `configuration=`).
- **Step 3 — One authoritative verifier for all roots.** Route every ROOT form (incl. koin-core
  `startKoin{}`, Compose `KoinApplication()`, `koinConfiguration{}` when root) through a single
  `validateFullGraph`. Aggregate `withConfiguration` into its base. Collapse the Phase 3.1 DSL-only path
  into it.
- **Step 4 — Enrich the carrier (Gate 2).** Annotation-arg carrier with requirements (incl. function
  providers) + origin. Unify the two param classifiers (`ParameterAnalyzer` / `KoinArgumentGenerator`) so
  the metadata is the byproduct of codegen.
- **Step 4c — Freshness (Gate 3).** Mandatory strictSafety at entry points; wire both IC trackers +
  hint↔source linking; build the `playground-apps` incremental stress matrix (add/remove/qualifier/scan,
  no clean) as the exit test. See §3b.
- **Step 5 — Flip A2 to structural-only.** Remove A2 graph-resolution errors; defer all to A3. BLOCKED on
  Gates 1+2+3 (Steps 2+3, 4, 4c).
- **Step 6 — Disclosure + `@Provided`.** Visible "graph unverified" warning for dynamic/unclassifiable
  roots; confirm `@Provided` skips dynamic dependencies cleanly.

## 4b. Step 1 results — baseline matrix (2026-07-22)

Ran a diagnostics probe matrix (`testData/diagnostics/entry_*.kt` + salvaged
`cross_module_scanned_class_koinapp_ok.kt`). Captured behavior:

| Probe | Entry point | Scenario | Baseline | Correct? |
|---|---|---|---|---|
| `entry_startkoin_core_scan_missing` | real `startKoin{}` | genuine miss | D001 @ AppModule | ✅ |
| `entry_koinapplication_core_scan_missing` | real `koinApplication{}` | genuine miss | D001 @ AppModule | ✅ |
| `entry_dynamic_modules_missing` | dynamic `modules(if…)` | genuine miss | D001 @ AppModule | ✅ |
| `entry_startkoin_core_scan_crossmodule_ok` | real `startKoin{}` | valid cross-module | D001 @ FeatureModule | ❌ false positive |
| `cross_module_scanned_class_koinapp_ok` | typed `@KoinApplication` | valid cross-module | D001 @ ServiceModule | ❌ false positive |

**Key finding (SURPRISE — corrects §1/§2 assumptions):** every diagnostic — genuine and
false — is attributed to a **module**, never the root. A2 (the per-module pass) is the **de
facto verifier**; A3/`validateFullGraph` emits essentially nothing user-visible — it runs,
marks modules validated, settles deferrals, but **A2 emits the D001 first and A3 cannot
un-emit it**. Confirmed against existing `cross_module_genuine_missing_d001` (→FeatureModule)
and `startkoin_missing` (→ServiceModule).

Implications:
- The false positive = A2 hard-erroring what A3 would resolve. Systemic to A2, on **both**
  typed `@KoinApplication` and real `startKoin{}`/`koinApplication{}` roots (not a typed-vs-lambda quirk).
- A2 currently carries **all** genuine-miss safety, including the **dynamic** module case A3
  fundamentally cannot verify. So Step 5 (gut A2) regresses probes 1–3 to silent unless A3 is
  first promoted from bookkeeping to primary emitter (Gate 1, heavier than "wire lambda forms")
  AND the dynamic case gets a per-module catch or the §Step-6 disclosure warning.
- Revised framing: **A3 must become the primary emitter before A2 is demoted** — the two are
  not a simple hand-off; A3 today barely emits.

## 5. Guards (per PR)

- Generated-diagnostic behavior is API: the A2→A3 shift changes what fires where → explicit release note.
- No blanket golden-file regeneration; each diagnostic move is an intentional, reviewed diff.
- JVM-green ≠ done: native + wasm compile required (duplicate-signature / KLIB errors are JVM-invisible).
- Re-run the playground compile-safety stress test for the reshaped behavior.
- **Clean-build green ≠ done (freshness):** the diagnostics harness is single-compilation and cannot see
  IC. Any change touching A3 or the carrier must also pass the §3b incremental stress matrix in
  `playground-apps` (add/remove/qualifier/scan, no clean).
