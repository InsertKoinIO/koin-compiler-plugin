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
