# Koin Playground Apps

Production-quality reference applications demonstrating Koin dependency injection with the Koin Compiler Plugin.

Two identical multi-module Android apps — one using **Annotations**, one using the **Safe DSL** — so you can compare approaches side by side.

## Apps

| App | DI Approach | Kotlin | Key Patterns |
|-----|------------|--------|--------------|
| `app-annotations/` | `@Singleton`, `@Module`, `@ComponentScan`, `@Configuration`, `@KoinApplication` | 2.4.0 | Annotation-driven with auto-discovery |
| `app-dsl/` | `single<T>()`, `factory<T>()`, `viewModel<T>()`, `create(::fn)`, `bind` | 2.4.0 | DSL with explicit module composition |
| `app-floor-2320/` | minimal DSL (JVM-only) | **2.3.20 (floor)** | Guard for the oldest supported Kotlin; quick probe of any line via `-PkotlinVersion=` |

Both use the **Koin Compiler Plugin** for compile-time dependency validation.

## Architecture

Inspired by [Now in Android](https://github.com/android/nowinandroid) — multi-module, offline-first, Compose UI.

```
app-*/
├── app/                    # Application + main ViewModels
├── core/
│   ├── common/             # Dispatchers, custom qualifiers
│   ├── model/              # Domain models
│   ├── database/           # Room database
│   ├── datastore/          # DataStore preferences
│   ├── network/            # HTTP client
│   ├── data/               # Repositories
│   ├── domain/             # Use cases
│   ├── analytics/          # Analytics
│   └── notifications/      # Notifications
├── feature/
│   ├── home/               # Home screen
│   ├── bookmarks/          # Bookmarks
│   ├── settings/           # Settings
│   └── detail/             # Detail (with nav args)
└── sync/
    └── work/               # WorkManager sync
```

## Stack

- **Koin** 4.2 + **Compiler Plugin** 1.2.0-Beta8
- Kotlin 2.4.0 (K2) — `app-floor-2320` stays on 2.3.20, the oldest supported version
- Jetpack Compose
- Room, DataStore, WorkManager
- Coroutines + Flow
- Navigation Compose

## Running

```bash
# Annotations app
cd app-annotations
./gradlew :app:installDebug

# DSL app
cd app-dsl
./gradlew :app:installDebug
```

## Compile-Safety Stress Test (run for every plugin version)

The core purpose of these two apps is a **stress test of compile-time safety**: hand-edit the source
to break the dependency graph in a specific way, recompile, and confirm the plugin **fails the build**
with the right `KOIN-Dxxx` diagnostic instead of letting it compile and crash at runtime. Run the full
checklist below against **both** `app-annotations` and `app-dsl` for every KCP release — it is a
per-version release gate — and again any time you touch A2/A3 compile-safety code.

Setup (once per run): pin the version under test in `gradle/libs.versions.toml`
(`koin-plugin = "<version>"`) for both apps.

For each scenario: make the edit → run the given `./gradlew` command → confirm the exact diagnostic →
**restore the edit** → rerun → confirm green again before moving to the next scenario. Don't stack
edits — one broken edge at a time, so a failure is unambiguous about which scenario caused it.

### ☐ 0. Baseline

- [ ] `app-annotations`: `./gradlew :app:compileDebugKotlin` succeeds clean, no plugin diagnostics.
- [ ] `app-dsl`: `./gradlew :app:compileDebugKotlin` succeeds clean, no plugin diagnostics.

### ☐ 1. Remove a used definition → `KOIN-D001`

| App | Edit | Command | Expected |
|---|---|---|---|
| `app-annotations` | Remove `@Singleton` from `core/data/…/repository/OfflineFirstNewsRepository.kt:12` | `./gradlew :app:compileDebugKotlin --rerun-tasks` (⚠️ see caveat below — the plain command can false-green) | **FAILS** `KOIN-D001: Missing dependency: …NewsRepository` |
| `app-dsl` | Comment out `core/data/…/di/DataModule.kt:26` — `single<OfflineFirstNewsRepository>() bind NewsRepository::class` | `./gradlew :app:compileDebugKotlin` | **FAILS** `KOIN-D001: Missing dependency: …NewsRepository` |

- [ ] annotations: fails as expected (with `--rerun-tasks`) → restored → green
- [ ] DSL: fails as expected → restored → green

No clean needed for the DSL app (fixed in 1.1.0-Beta3 — see History below); the aggregator's
(`:app`'s) full-graph check catches it on an incremental rebuild.

> **History:** DSL removal detection previously required `:<module>:clean` because DSL hints were
> emitted one class *per definition* (`…Dsl_singleKt.class`); Kotlin IC regenerated hints for the
> remaining defs but never deleted a *removed* def's orphan class, so an incremental rebuild passed
> silently. Fixed in **1.1.0-Beta3** by batching each module's DSL hints into one
> `koin_dsl_hints_<module>.kt` file regenerated wholesale (same shape the annotation module-scan hints
> always used) — a removed def leaves no orphan class.

> **⚠️ `app-annotations` caveat (found 2026-08-28, plugin 1.2.0-Beta7, confirmed daemon-isolated —
> real, not a shared-daemon artifact).** A *plain* `./gradlew :app:compileDebugKotlin` can false-green
> this exact scenario: `:core:data:compileDebugKotlin` correctly re-executes and regenerates its
> `@ComponentScan` hint excluding the removed definition (verified: 5 entries instead of 6), but
> `:app:compileDebugKotlin` — which also re-executes, this is not a Gradle up-to-date skip — still
> validates against a stale view of that hint and reports all dependencies satisfied. Only
> `--rerun-tasks` (which discards Kotlin's own incremental-compilation cache, not just Gradle's task
> cache) reliably catches it. **Narrow, not systemic**: scenarios 2 and 3 on this same app caught
> their breaks on the very first plain build, no rerun needed — this reproduces specifically for
> "annotation removed, container class kept, hint's definition count shrinks by one." Not yet fixed;
> always use `--rerun-tasks` for this scenario on `app-annotations` until it is.

### ☐ 2. Remove a transitive includes edge in a dependency module → `KOIN-D001`

Needs a module that reaches the root through **exactly one** transitive path, with no direct edge
from the root — otherwise the direct edge keeps the graph complete and the build correctly stays
green (not a bug, just the wrong module picked).

| App | Edit | Command | Expected |
|---|---|---|---|
| `app-annotations` | Drop `includes = [DatabaseModule::class]` from `core/database/…/di/DaosModule.kt:11` (`DaosModule` is `@Configuration`-labeled and is `DatabaseModule`'s only path to the root; `DatabaseModule` itself carries no `@Configuration`) | `./gradlew :app:compileDebugKotlin` | **FAILS** `KOIN-D001` naming a `AppDatabase`/dao dependency |
| `app-dsl` | ⚠️ **currently not exercisable** — every module in `app/…/di/AppModule.kt`'s `includes(...)` list (line 33) also has a direct edge from the root, so there is no sole-transitive-path module left. Restructure a module to remove its direct edge before running this, or skip with a note. | — | — |

- [ ] annotations: fails as expected → restored → green
- [ ] DSL: confirm still N/A, or restructure and run

> This is the scenario the annotations side is being brought to parity on (see
> `ANNOTATION_INCLUDES_HINT_PREFIX` in `KoinPluginConstants.kt`) — run it explicitly on every release
> after this branch lands, not just the DSL side, since it previously had no coverage at all here.

### ☐ 3. Remove a module from the root's module list, where a call site still resolves it → `KOIN-D002`

This is the one that used to compile and crash at runtime — the injected type resolved against a
module nobody loaded.

| App | Edit | Command | Expected |
|---|---|---|---|
| `app-annotations` | Remove `@Configuration` from `app/…/di/ActivityModule.kt:12` (drops it from auto-discovery; call site is `app/…/MainActivity.kt:25` — `by inject()` for `ActivityTracker`) | `./gradlew :app:compileDebugKotlin` | **FAILS** `KOIN-D002` at the `MainActivity.kt:25` inject site |
| `app-dsl` | Comment `activityModule` out of `app/…/di/AppModule.kt:33`'s `includes(...)` (call site is `app/…/MainActivity.kt:22` — `by inject()` for `ActivityTracker`) | `./gradlew :app:compileDebugKotlin` | **FAILS** `KOIN-D002` at the `MainActivity.kt:22` inject site |

- [ ] annotations: fails as expected → restored → green
- [ ] DSL: fails as expected → restored → green

Note: Gradle suppresses `w:` lines on a failing task, so the `KOIN-W001` that fires alongside the
D002 will not appear in the console — don't treat its absence as a problem.

### ☐ 4. Empty a module entirely → KNOWN LIMITATION for DSL; **not currently reproducing for annotations**

If a module loses its last `includes()`/`includes=[...]` edge **and** its last definition, the DSL
app's incremental rebuild does **not** detect it — the build passes and the missing providers surface
at runtime; `:<module>:clean` does not help either, only a full `clean` (with `--no-build-cache`)
catches it. **Re-verified 2026-08-28 on 1.2.0-Beta7: this still holds for `app-dsl`.** The same check
on `app-annotations`, however, was caught by a *plain incremental* build this time — no clean needed
— contradicting the previously-unconditional claim here for that app. Root cause for the apparent fix
was not deliberately investigated; it's plausibly an incidental side effect of hint-batching /
multi-hop `@Configuration` discovery work already on this branch, unrelated to whatever intentionally
changed. **Treat the annotation-path fix as observed, not guaranteed — re-run this scenario on both
apps before relying on either claim for a release**, this is not something to "fix" by editing further.

| App | Edit | Expected without clean | Expected after full clean |
|---|---|---|---|
| `app-annotations` | Empty `core/database/…/di/DaosModule.kt` completely: drop `includes = [DatabaseModule::class]` and all three `@Singleton` functions | ⚠️ **Currently caught immediately** — incremental `./gradlew :app:compileDebugKotlin` **FAILS** `KOIN-D001` (no clean needed, as of 2026-08-28 verification) | `./gradlew clean :app:compileDebugKotlin --no-build-cache` **FAILS** identically |
| `app-dsl` | Empty `core/datastore/…/di/DataStoreModule.kt`: drop `includes(dispatchersModule)` (line 27) and both definitions (lines 29-30) | incremental `./gradlew :app:compileDebugKotlin` **passes silently** (the gap, confirmed still present) | `./gradlew clean :app:compileDebugKotlin --no-build-cache` **FAILS** `KOIN-D001` |

- [ ] annotations: confirm whether incremental still catches it (if it regresses to silent-pass, that's the historical gap returning) → full clean catches it either way → restored → green
- [ ] DSL: incremental green (gap reconfirmed) → full clean catches it → restored → green

> `javap` on the emptied module's `classes.jar` shows the edge/definition genuinely gone — the
> *artifact* is correct. The residual (on `app-dsl`) is K2 re-resolving a changed hint signature within
> one incremental session, past what the plugin can reach. Scope is narrow: changing *one edge among
> several* (scenario 2/3 above) propagates correctly on a no-clean rebuild on both apps; it's only the
> *completely-empty* case that needs the full clean — and, per the above, apparently only on the DSL
> path as of this verification.
>
> **Correction (2026-08-28): the "simpler single-definition leaf" fallback for `app-dsl`
> (`NotificationsModule.kt:10` / `AnalyticsModule.kt:10`) does NOT reproduce this gap** — neither leaf
> has an `includes()` edge to begin with, so removing their one definition is already caught
> incrementally (that's the 1.1.0-Beta3 DSL orphan-hint fix working correctly, a different and already-
> fixed case). Only a module losing an `includes()` edge *together with* its last definition
> (`DataStoreModule`, as specified above) reproduces the actual gap — don't substitute the leaf
> shortcut.

## Key Patterns Covered

- Application bootstrap (`startKoin<T>` / `startKoin { }`)
- Module composition with `includes()` / `@Module(includes = [...])`
- Custom qualifier annotations (`@Dispatcher` with enum)
- Interface binding (`bind` / automatic)
- ViewModel with `SavedStateHandle` and `@InjectedParam`
- WorkManager integration (`@KoinWorker` / `worker<T>()`)
- Activity scopes (`AndroidScopeComponent`)
- Compose ViewModel injection (`koinViewModel()`)
- External library wrapping (Room, Retrofit patterns)

## Documentation

- [Koin Documentation](https://insert-koin.io/docs/intro/index)
- [Compile-Time Safety](https://insert-koin.io/docs/reference/koin-compiler/compile-safety)
- [Compiler Plugin Setup](https://insert-koin.io/docs/setup/compiler-plugin)

## License

Apache 2.0
