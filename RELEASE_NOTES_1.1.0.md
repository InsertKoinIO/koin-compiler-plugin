A compile-safety architecture release: **A2 (per-module validation) is removed entirely** in favor
of a single, authoritative full-graph check at each Koin entry point (A3). This closes a real,
measured false-positive class, at the cost of leaf modules with no entry point of their own now
getting **no compile-time safety diagnostics** until something assembles a real graph around them.
Also ships incremental-compilation freshness hardening, `allWarningsAsErrors` compatibility, and a
collision-safe hint-file-naming scheme.

## ⚠️ Behavior change — A2 per-module validation removed (#32, #51)

**Why.** A module validated in isolation cannot know how it will be wired into a larger app. This
stopped being theoretical: `:core:notifications` in a real playground app genuinely false-positived
on a dependency (`PeerService`) that a peer module provides — with no Gradle edge between the two,
the two are only unified downstream at the app's entry point. Per-module (A1: local + includes, A2:
`@Configuration`-sibling) validation cannot see that far and reported a hard `KOIN-D001` for a
dependency that resolves correctly once the real app assembles both modules together. Rather than
keep tuning the per-module oracle around each new false-positive shape, A2 (and its "defer iff a
provider exists somewhere" oracle) is deleted outright. A3 — the full-graph check that runs at
`startKoin`/`koinApplication`/`@KoinApplication` — is now the **sole** compile-safety verifier.

**What this means for you:**
- **Rooted compiles (an app module with a real `startKoin`/`koinApplication`/`@KoinApplication`)**:
  more accurate. Genuine cross-module false positives like the peer-provider case above disappear;
  `KOIN-D001` now always shows the real, assembled graph.
- **Leaf/library modules with no Koin entry point in their own compilation**: `KOIN-D001`
  (missing dependency), `KOIN-D004` (circular dependency), `KOIN-D005`/`KOIN-D006` (parametersOf
  shape mismatches resolved via the graph), and `KOIN-P001` (missing `@PropertyValue`) are now
  **silent** in that compilation — not because the module is safe, but because compile-time cannot
  know how it will be assembled downstream. The graph is still checked, correctly, at the real
  entry point once one exists in the compilation. This is disclosed via a default-visible
  (INFO-severity) message rather than failing silently; see `logSeverity` below to control its
  visibility.
- **`KOIN-W002`** (the old "deferred, no provider hint found anywhere" warning) is deleted — there
  is no more deferral machinery to warn about.
- Circular-dependency detection (`KOIN-D004`) going silent for a leaf module is intentional, not a
  regression: detecting a cycle requires seeing the whole graph, and a same-module-only check was
  never a complete cycle detector even under the old A2 (it only ever saw local/sibling visibility).

**Full account, including the design docs this reverses:** `docs/COMPILE_SAFETY_A3_PLAN.md`
(superseded-banner) and `docs/COMPILE_TIME_SAFETY.md`.

## 🐛 Fixes

### `KOIN-D001` now names the real culprit module and source location
Missing-dependency errors now carry `file:line` for the failing definition and the actual owning
module's name (previously degraded to a generic app/root label once every `KOIN-D001` funnels
through the one remaining A3 check). Also fixed: attribution for `FunctionDef`-shaped definitions
used a bare simple name, which could collide across same-named modules in different packages — now
uses the fully-qualified name.

### `KOIN-D001` deduplication across multiple entry points
A module reachable from more than one `startKoin`/`koinApplication`/`@KoinApplication` in the same
compilation (common in test-apps: ~9 entry points is typical) previously re-validated and re-emitted
the same missing-dependency error once per entry point. Now deduplicated by (definition, missing
requirement), so a shared module with one real problem reports it exactly once.

### D005/D006 (parametersOf shape checks) no longer require a Koin entry point
The `parametersOf(...)` argument-count/presence check is graph-independent — the expected slots come
from the target's own constructor, not from an assembled graph — so it now runs unconditionally
instead of being skipped whenever no entry point is present in the compilation, matching its actual
data dependency. `KOIN-D002` (call-site *resolution*) correctly keeps requiring an assembled graph
and stays silent without one — the two diagnostics no longer share a gate they don't share a
dependency on.

### Cross-module qualifier and typed-scope resolution verified under the new sole-verifier design
New regression coverage confirms A3 matches `@Named` qualifiers and typed `@Scope(X::class)` keys
correctly across Gradle module boundaries, not just "some provider of this type exists somewhere" —
this matters more now that A3 has no per-module fallback to catch a wrong match.

**Known pre-existing limitation, found while writing this coverage (not new, not fixed this
release):** `BindingRegistry.findProvider`'s scope-visibility check only matches a **typed**
`@Scope(X::class)`; a **named** `@Scope(name = "...")` provider has no `scopeClass` and is treated
as visible everywhere regardless of name.

## 🔒 Incremental-compilation freshness (Gate 3)

Removing A2's leaf-local checking made A3's own freshness across incremental (IC) rebuilds load-
bearing in a way it wasn't before — these changes close that gap:

- **`strictSafety` is now mandatory once an aggregator is auto-detected**, not opt-in. Previously,
  an explicit `strictSafety = false` silently won over the plugin's own `startKoin`/
  `koinApplication`/`@KoinApplication` detection, letting an aggregator's `compileKotlin` stay
  cacheable/up-to-date even when the DI graph changed underneath it (lambda-body DSL edits and
  new `@ComponentScan`-covered files don't register as ABI changes IC can see). `strictSafety = true`
  still works everywhere; the new escape hatch for a genuine detector misfire (the marker appears
  only in a comment/string, not a real entry point) is `strictSafetyForceOff = true` — a separate,
  explicit acknowledgement from a plain `false`.
- **Extended IC tracker linking**: `KoinDSLTransformer`'s 5 DSL definition call sites now register
  with `ExpectActualTracker` (alongside the existing `LookupTracker` calls), matching the pairing
  `KoinAnnotationProcessor`/`KoinStartTransformer` already had — closes another source of stale
  incremental state around DSL hint files.
- **A theorized `@ComponentScan` new-file freshness gap did not reproduce**: adding a new
  `@Singleton`/`@Factory` class to a scanned package is itself a source-set input change, which
  Gradle already invalidates the owning module's `compileKotlin` task for, independent of anything
  Koin-specific — verified live on a real playground app. No plugin-side fix was needed here.
- **Known limitation, unchanged by this release**: a module going **completely empty** (its last
  `includes()` or its last local definition removed, with nothing replacing it) is not detected
  incrementally without a full clean + `--no-build-cache`. This is a K2-internals residual (a
  keep-alive hint's signature not being re-resolved within one IC session), not a missing source
  edge — see `playground-apps/README.md`'s "Known limitation" note.

## 🔇 `allWarningsAsErrors` / `-Werror` compatibility (#73)

Informational plugin output (`userLogs`/`debugLogs` messages, the `@Monitor`-tracing-enabled
summary) was emitted at WARNING severity unconditionally, which fails a build compiled with
`allWarningsAsErrors` even though none of it is a real diagnostic.

- New **`logSeverity`** option (`"warning"` default, or `"info"`) covers all of the above.
- New, **separate** **`versionCheckSeverity`** option covers only the Kotlin-version-compatibility
  warning ("you're on an unverified Kotlin version") — kept independent because muting informational
  noise shouldn't also silence a real compiler-compatibility risk; set it to `"info"` only after
  assessing that risk yourself.
- Real diagnostics (`KOIN-Dxxx`/`KOIN-Wxxx`/etc.) are unaffected by either setting — they always
  report at their own severity.

```kotlin
koinCompiler {
    logSeverity = "info"           // downgrade informational output, default "warning"
    versionCheckSeverity = "info"  // downgrade the version-compatibility check, default "warning"
}
```

## 🧷 Hint-file collision safety (#75)

Five internal call sites that generate synthetic hint file names for cross-module discovery used
unbounded-length, collision-prone name sanitization (e.g. `p.q_r.mod` and `p.q.r_mod` both
flattening to `p_q_r_mod`). All five now go through one shared utility: a bounded, readable prefix
plus a 64-bit hash suffix computed over the untruncated input, so truncation itself can never cause
a collision. (The frozen, cross-version-reconstructed *function*-name encoding — `flattenFqNameForHint`
— is untouched; only file names, which have no external reconstructors, changed shape.)

New diagnostic **`KOIN-D008`** hard-errors on a same-compilation hint-name collision (e.g. two
zero-parameter keep-alive hints sharing a signature, a real KLIB `SignatureClashDetector` failure
mode) — there is no legitimate scenario where two distinct modules should collide, so there's no
opt-out. Detecting a **cross-Gradle-module** collision is a known, explicitly deferred gap (would
need to run at the entry-point aggregator over already-decoded ids) — documented, not silent.

## ✅ Compatibility

- **Koin**: 4.2.0+
- **Kotlin**: verified range 2.3.0–2.3.10 (see `CLAUDE.md` for the version-gate policy)

## 📦 Install

```kotlin
plugins {
    id("io.insert-koin.compiler.plugin") version "1.1.0"
}
```

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.0.2...1.1.0
