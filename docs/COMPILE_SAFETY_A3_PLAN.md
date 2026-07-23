# Compile Safety — A3 Reshape Plan

> Status: design, PR1 landed (metadata contract). Organizing spine: a **common verifier meta-model** + a **gap matrix**.
> Grounding this session: three code audits, a comparable-K2-DI-plugin carrier study, an A2 collect-only
> spike (falsified the blunt version), a Step-1 baseline diagnostics matrix, a playground reality-check,
> a red-team review, and a meta-model critique. Evidence in §9.

## 1. Principle

A2 **collects** metadata + generates code; A3 **verifies** the assembled graph at the entry point. Both
sides populate **one source-agnostic meta-model** (§2), consumed by **one verifier**. The reshape does not
delete the `Definition` hierarchy — the meta-model is a *verifier projection* over it (codegen keeps its
IR-bearing fields).

**The boundary that scopes it — "is module membership in ABI?"** (the correction the red team forced):

- **Annotation roots** — `@KoinApplication(modules=[…])` class lists + `@Configuration` labels + `@Module(includes=…)` are **ABI**, so A3 can authoritatively reconstruct the loaded set → **A3 verifies, A2 collect-only.**
- **DSL / Compose-`koinConfiguration`** — membership lives in `includes()`/`modules()` **lambda bodies (non-ABI)**. A3 verifies **same-compile** graphs today; **cross-Gradle-module** membership is lost → needs the **includes-edge carrier** (§5) to reach annotation parity.
- **Entry-point-less leaves** (e.g. KMP `@Module` libraries compiled without their app) — no A3 in the compilation → **keep A2 + a *completed* oracle** as the net (hard-error genuine-local misses, defer cross-module). The oracle is **not deleted** — the ClassDef-exclusion *tuning* was the hack; the oracle *concept* (defer iff a provider exists somewhere) is load-bearing for cases A3 structurally cannot reach.

### The three gates (exit criteria before A2 stops emitting for a given path)
- **Gate 1** — A3 actually EMITS (today it's bookkeeping; A2 is the de-facto emitter, §9) at the root with culprit `origin`, for all statically-resolvable roots.
- **Gate 2** — the carrier carries the "need" cells (requirements incl. function providers, origin, DSL includes-edges).
- **Gate 3** — freshness (§6): A3 stays correct across incremental builds.

## 2. The common meta-model (the spine — a verifier *projection*, not a replacement for `Definition`)

Both annotation and DSL populate the same shapes; only *discovery* differs. `Definition` stays for codegen
(it carries `createdAtStart`, `moduleInstance`, `registrationSourceFile` (#32 IC anchor), raw IR refs the
verifier has no reason to hold).

```
Definition {                       // provider node (projection)
  origin: SourceOrigin             // module fqn + file + line
  providedType; bindings; qualifier
  scope: ScopeKey                  // typed | named | archetype | root   (FIX-1)
  kind
  requirements: [Requirement]      // WHOLE struct; validate the requiresValidation() subset (FIX-2)
  ownerModuleId
}
Module {
  id: ModuleId?                    // null ⇒ always-loaded / unclassifiable (FIX-6)
  origin; kind (ANNO_CLASS | DSL_VAL); labels (@Configuration | ∅)
  declaredDefinitions: [DefId]
  includes: [ModuleId]             // topology edge
}
EntryPoint { origin; classification (Root | Dynamic); loadedModules (label-resolved, FIX-8) }
CallSite  { origin; requiredType; injectedParamSlots }        // D005/D006 (FIX-7)

Verifier.verify(entry, world)
  world = { propertyDefaults,        // PropertyValueRegistry → KOIN-P001 (FIX-3)
            trustedTypes }           // framework whitelist ∪ @Provided ∪ Scope-receiver (FIX-4)
```

Verifier job (source-agnostic): `entry.loadedModules` + transitive `Module.includes` → loaded set → union
`declaredDefinitions` → provided index (keyed by type + qualifier + `ScopeKey`) → for each loaded
`Definition`, resolve the `requiresValidation()` subset of `requirements` against the index and
`trustedTypes` → emit at `origin`. Plus cycle detection (keep the `isLazy` edge exclusion) and `CallSite`
`parametersOf` checks.

**Baked-in fixes (from the meta-model critique):** FIX-1 `ScopeKey` unifies typed/named/archetype/root
(a release-noted matching improvement — today only `scopeClass` is matched); FIX-2 keep `Requirement`
whole; FIX-3 `propertyDefaults` input; FIX-4 `trustedTypes` input; FIX-6 nullable `ModuleId` semantics;
FIX-7 first-class `CallSite`; FIX-8 label-resolved `loadedModules` preserving the label-mismatch invariant;
FIX-9 function-provider `requirements` must be populated (not `emptyList()`).

## 3. The gap matrix (what each side must produce to fill the model)

| Model element | **Annotation** cross-module source | **DSL** cross-module source |
|---|---|---|
| Definition: type/bindings/qualifier/scope | ✅ `definition_*` / `componentscan_*` hints | ✅ `dsl_*` hints |
| Definition.**requirements** | ⚠️ re-derived from class ABI; `ExternalFunctionDef` empty (FIX-9) | ⚠️ stored locally (PR1); cross-module needs carry |
| Definition.**origin** | ❌ not carried (PR1 = local only) | ❌ not carried |
| Module **identity** | ✅ class FqName | ⚠️ top-level `val` FqName ok; inline/local/fn-returned = null (FIX-6) |
| Module.**includes** (topology) | ✅ `@Module(includes=…)` is ABI | ❌ **lambda body → includes-edge hint** |
| Module.labels (`@Configuration`) | ✅ `configuration_*` hints | n/a |
| Root.**loadedModules** | ✅ `@KoinApplication(modules)` ABI (+ label resolution) | ⚠️ `startKoin{modules()}` same-compile ok |

**Per-side gap:**
- **Annotation** — model is almost fully populatable from existing ABI+hints. Fills needed: **origin**, **function-provider requirements** (FIX-9), and the **@ComponentScan-new-file freshness** hole (§6). Then A3 emits; A2 → collect-only for rooted compiles, A2+oracle-net for leaves.
- **DSL** — fills needed: **includes-edge topology** (DSL's *only* membership mechanism — no `@ComponentScan`/`@Configuration` shortcuts), **origin**, function-provider requirements. Then DSL flows through the identical verifier. Same-compile already works today; the carrier closes cross-module.

## 4. EntryPoint classification & forms

Every detected entry point is a **Root**: its assembled closure must be a complete, self-consistent graph
on its own. `koinConfiguration<T>()` / `koinApplication` / `startKoin` are each verified standalone —
**separate config calls do NOT pool to complete each other's missing references** (deliberate design: a
config missing a dependency fails, rather than silently relying on a sibling). `withConfiguration { }` is
**also a sealed self-consistent unit** — no dependency outside the config (same rule). (Open at the
verification step: whether a chained `koinApplication{A}.withConfiguration{B}` seals B standalone or the
assembled {A+B} — since `withConfiguration` merges into the app at runtime, this decides a false-positive
boundary.) The only non-Root class is **Dynamic** (`modules(if…)`, runtime lists → module set not statically resolvable →
disclose "unverified", never silent). So classification is **Root | Dynamic** (no Fragment — the
self-consistency rule dissolves the cross-compile fragment-detection problem; no Koin-core API change).
Forms: `startKoin` / `koinApplication` / `koinConfiguration` / `withConfiguration` / Compose
`KoinApplication()` / `@KoinApplication`.

Playground reality (§9): the flagship apps use the *harder* static forms — real koin-core `startKoin{}`
(not yet routed to A3) and **bare `@KoinApplication` + `@Configuration` discovery** (not
`@KoinApplication(modules=[…])`). Gate 1 must handle both. No dynamic-module assembly was found in any
playground app; no fragment/`withConfiguration` coverage exists (untested classifier branch).

## 5. Carrier — filling the "need" cells, portably

Resource files are dead on KMP. Decision: **typed-mirror + annotation-arg, NO protobuf, NO `@Metadata` blob.**
Do not serialize the graph — each provider exposes its own edges; the verifier reconstructs the chain.

- **Requirements → a generated typed mirror declaration** (param types + per-param annotations ARE the
  requirement list). Closes FIX-9 (function providers). Class providers already expose edges via constructor ABI.
- **Origin → annotation argument** (module FqName + file/line).
- **DSL includes-edge (topology) → a new includes-edge hint**: per `val a = module { includes(b) }`, emit an
  ABI record `a → [b]` referencing `b` by stable `ModuleId`. This is the DSL membership carrier — brings DSL
  to the parity annotations get free from `@Module(includes=…)`.
- **Channel** stays the existing generated-declaration hints package. The `@Metadata` route is a separate
  1.1+ upgrade (only payoff: IC freshness) behind a native/wasm gate — never for carrying the graph.
- **Per-cell survival is one question, one harness:** does this cell survive cross-module on klib/native?
  Every "need" cell (origin, typed-mirror requirements, includes-edge) gets a native+wasm survival box test
  **before** we rely on it (the `@SerialInfo` precedent is same-compile FIR, not cross-module IR read — not
  proof for our path).

## 6. Gate 3 — freshness (incremental compilation)

A3-sole-verify removes A2's per-module freshness-robustness. Levers:
1. **strictSafety MANDATORY** whenever an entry point is present (was opt-in). Bounded cost: only the aggregator recompiles.
2. **Both `LookupTracker` + `ExpectActualTracker`** + **link each hint → source class** so change/removal forces recompile/removal (cures the DSL orphan-hint false-green).
3. **`@ComponentScan` new-file** (no source edge) — hard K2 residual: strictSafety re-run + package-scope lookup; else **disclose**, never silent.

**Exit test — incremental stress matrix in `playground-apps`** (real Gradle modules + IC): for each of
{add def, **remove** def, change qualifier, add scanned class} in a dependency module, rebuild **without a
clean**, assert A3 reacts correctly. The current bed covers only *remove-with-clean* and structurally can't
run the no-clean DSL leg (orphan-hint bug it documents) — the rest must be built. **Clean-build green ≠ done.**

**Decision:** IC-trackers + mandatory strictSafety, NOT a bespoke content-hash dirty tracker.

## 7. Diagnostics the single verifier MUST preserve (guard — silent loss is the worst failure class)

| Diagnostic | Today at | Preservation requirement |
|---|---|---|
| **KOIN-D001** MissingBinding (+ "similar binding" hint) | `BindingRegistry.kt:656` | move to A3 with culprit `origin`, not aggregator |
| **KOIN-D004** CircularDependency (Lazy-broken) | `BindingRegistry.kt:494` | keep `isLazy` cycle-edge exclusion (`:537`) |
| **KOIN-P001** MissingPropertyValue | `BindingRegistry.kt:398` | `propertyDefaults` must be a verifier input (FIX-3) |
| **KOIN-D005/D006** parametersOf + MissingInjectedParams | `CallSiteValidator.kt:492` | needs `CallSite` node (FIX-7) |
| **KOIN-W001** UnreachableModule (DSL) | `CallSiteValidator.kt:464` | needs stable `ModuleId` + includes edges (FIX-6) |
| MissingCallSite (koinInject cross-module) | `CallSiteValidator.kt:135` | keep its hint round-trip; don't fold into the graph loop |
| Qualifier-mismatch (`@Named`/typed) | `BindingRegistry.kt:644` | preserve in qualifier/`ScopeKey` matching |
| Skip-sets: whitelist, `@Provided`, `@ScopeId`, Scope receiver | `BindingRegistry.kt:117,413`; `ParameterAnalyzer.kt:149` | `trustedTypes` (FIX-4) |

**Correctly deleted once gates hold (each release-noted):** `KOIN-W002` + deferral machinery, the
`providerHintTypeFqNames` oracle *tuning* (kept as the leaf net, completed), `hasCrossModuleHint`.

## 8. Work breakdown — fill the matrix against one verifier

- **PR1 — DONE (metadata contract).** `SourceOrigin` + `requirements`/`origin` on `Definition`, additive, green, zero golden diffs. Parked in a worktree; salvage onto branch. Valid in every version of this design.
- **Fill annotation cells:** origin in the carrier; function-provider requirements (typed mirror); route all annotation roots (incl. bare `@KoinApplication`+`@Configuration`) through the one verifier; A3 emits (Gate 1).
- **Fill DSL cells:** includes-edge hint (topology) + consumer reconstruction (stop `null ⇒ reachable` over-approximation); origin. Proven by the cross-module forgot-`include` RED test.
- **Reify `EntryPoint` + classifier** (Root/Dynamic) — replaces `hasKoinEntryPoint`. Internal plugin model
  only; works with existing Koin entry-point APIs (no API extension).
- **Freshness (Gate 3):** trackers + mandatory strictSafety + the incremental stress matrix.
- **Then, per path where its gates hold:** demote A2 to collect-only (rooted annotation compiles); keep A2+completed-oracle net for leaves. Never delete a diagnostic in §7 without its replacement proven.

## 9. Evidence (this session)

**Step-1 baseline matrix** (`testData/diagnostics/entry_*.kt` + salvaged `cross_module_scanned_class_koinapp_ok.kt`):
every diagnostic is attributed to a **module**, never the root → **A2 is the de-facto emitter; A3 emits
nothing user-visible today.** Two probes documented the cross-module scanned-class false positive on both
typed `@KoinApplication` and real `startKoin{}`.

**Red team:** the "A3 sole verifier, delete A2+oracle" design is sound for the annotation path, **unsound if
generalized to DSL/Compose/entry-point-less leaves** (non-ABI or absent membership). Recalibrated by the
follow-up: DSL is *already* entry-point-only (no per-module DSL pass to lose), so the residual is the
**pre-existing cross-module transitive-includes over-approximation**, not a regression the reshape
introduces — closed by the §5 includes-edge carrier. Surviving structural point: **keep the oracle as the
leaf net.**

**Playground:** no dynamic module assembly anywhere (the "degrades to unverified" fear did not materialize
on the sample); flagship apps use the harder static forms; zero fragment coverage; freshness bed is ¼ of the
§6 matrix and can't run the no-clean DSL leg.

**Meta-model critique:** adopt the common model WITH FIX-1…9 (§2); it's a genuine shared core, not a
trench-coat union; the 3 silent-loss risks (P001, W001, D005/D006) are the must-fixes.
