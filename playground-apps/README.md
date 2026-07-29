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

- **Koin** 4.2 + **Compiler Plugin** 1.0.2-Beta1
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

The core purpose of these two apps is a **stress test of compile-time safety**: randomly comment out
(or delete) a definition that something else depends on, recompile, and confirm the plugin **fails the
build** with `KOIN-D001` instead of letting it compile and crash at runtime. Run this against
**both** `app-annotations` and `app-dsl` for every KCP release — it is a per-version release gate.

### Procedure

1. Pin the version under test in `gradle/libs.versions.toml` (`koin-plugin = "<version>"`).
2. Comment out a used definition in a `core` module, e.g. `core/data`:
   - **annotations** — the `@Singleton` on `OfflineFirstNewsRepository`
   - **DSL** — `single<OfflineFirstNewsRepository>() bind NewsRepository::class` in `DataModule.kt`
3. Recompile and confirm the build **fails** with `KOIN-D001: Missing dependency: …NewsRepository`.
4. Restore the line; confirm the build passes again.

### No clean needed (as of 1.1.0-Beta3 — orphan-hint fix)

```bash
# DSL — incremental is fine; a removed definition is caught without any clean
cd app-dsl
./gradlew :app:compileDebugKotlin                        # MUST fail with KOIN-D001

# Annotations — same, caught at the owning module during its own compile
cd app-annotations
./gradlew :app:compileDebugKotlin                        # MUST fail with KOIN-D001
```

### Expected result

| App | After commenting a used definition | Where it's caught |
|-----|-----------------------------------|-------------------|
| `app-annotations` | build **FAILS** with `KOIN-D001` (incremental is fine) | the definition's own module (A2, real class symbols) |
| `app-dsl` | build **FAILS** with `KOIN-D001` (incremental is fine) | the aggregator (`:app`) via cross-module hints |

> **History:** DSL removal detection previously required `:<module>:clean` because DSL hints were
> emitted one class *per definition* (`…Dsl_singleKt.class`); Kotlin IC regenerated hints for the
> remaining defs but never deleted a *removed* def's orphan class, so an incremental rebuild passed
> silently. Fixed in **1.1.0-Beta3** by batching each module's DSL hints into one
> `koin_dsl_hints_<module>.kt` file regenerated wholesale (same shape the annotation module-scan hints
> always used) — a removed def leaves no orphan class. Verified incremental (no clean), `:module:clean`,
> and full-clean all detect. A full clean / `--rerun-tasks` remains a safe belt-and-suspenders check.

### Also worth commenting out (as of 1.1.0-Beta8/Beta9)

Removing a *definition* is not the only way to break a graph. Two more edits belong in the sweep:

```bash
# 1. Remove a transitive `includes()` edge in a dependency module.
#    app-dsl: drop `databaseModule` from core/data/…/DataModule.kt's includes(...)
./gradlew :app:compileDebugKotlin        # MUST fail with KOIN-D001 (incremental is fine)

# 2. Remove a module from the root's includes(), where a call site still resolves it.
#    app-dsl: comment `activityModule` out of app/…/AppModule.kt
./gradlew :app:compileDebugKotlin        # MUST fail with KOIN-D002 at the inject() call site
```

> **Edit 1 is currently stale for `app-dsl`'s topology (found during 1.1.0 release verification).**
> `app/…/AppModule.kt` now ALSO lists `databaseModule` directly in its own `modules(...)`/`includes(...)`
> list, alongside `DataModule.kt`'s transitive edge — so dropping the transitive edge alone leaves the
> graph genuinely complete via the direct edge, and the build correctly succeeds (not a plugin bug,
> just doesn't exercise "sole transitive edge" anymore). Pick a module that reaches the root through
> exactly one transitive path with no direct edge before running this check, or restructure `app-dsl`
> to restore that shape.

Edit 2 is the one that used to compile and crash at runtime — `by inject<ActivityTracker>()` resolved
against a module nobody loaded. Note that Gradle suppresses `w:` lines on a failing task, so the
`KOIN-W001` that fires alongside the D002 will not appear in the console.

> **Known limitation — a module that goes COMPLETELY empty.** If a `module { }` val loses its last
> `includes()` *and* has no definitions of its own, an incremental rebuild does **not** detect it:
> the build passes and the missing providers surface at runtime. `:<module>:clean` does not help
> either; only a full `clean` (with `--no-build-cache`) catches it.
>
> The plugin emits a zero-parameter keep-alive hint for exactly this case, so the *artifact* is
> correct — `javap` on the module's `classes.jar` shows the edge gone. The residual is K2 re-resolving
> a changed hint signature within one incremental session: the consumer keeps seeing the old
> signature even though the jar it compiles against no longer contains it. Both tasks re-execute and
> clearing the consumer's IC caches does not help, so this sits past what the plugin can reach.
>
> Scope is narrow: changing *one edge among several* propagates correctly (verified — 3 correct
> `KOIN-D001` on a no-clean rebuild). It is also not specific to `includes()` — the same shape applies
> to a module whose last *definition* is deleted, which predates the includes-edge carrier. If you are
> deliberately emptying a module, run a full clean before trusting a green build.

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
