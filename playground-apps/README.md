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

### ⚠️ DSL requires cleaning the edited module first

DSL cross-module detection reads generated hint classes (`org/koin/plugin/hints/…Dsl_singleKt.class`)
from the dependency's output. Kotlin **incremental** compilation regenerates hints for the definitions
that remain but does **not delete** the hint class of a *removed* definition — it survives as an orphan
and makes the deleted provider still look present. An incremental-only rebuild therefore **passes
silently** (false green → runtime crash). Always clean the edited leaf module for the DSL app:

```bash
# DSL — clean the edited module so stale hints can't mask the removal
cd app-dsl
./gradlew :core:data:clean :app:compileDebugKotlin      # MUST fail with KOIN-D001

# Annotations — no clean needed; caught at the owning module during its own compile
cd app-annotations
./gradlew :app:compileDebugKotlin                        # MUST fail with KOIN-D001
```

### Expected result

| App | After commenting a used definition | Where it's caught |
|-----|-----------------------------------|-------------------|
| `app-annotations` | build **FAILS** with `KOIN-D001` (incremental is fine) | the definition's own module (A2, real class symbols) |
| `app-dsl` | build **FAILS** with `KOIN-D001` **after `:<module>:clean`** | the aggregator (`:app`) via cross-module hints |

> The DSL clean requirement is a known **incremental-compilation limitation** (orphaned generated hint
> classes), not a validation-logic bug — a clean / `--rerun-tasks` build catches every case, in every
> version tested. Tracked for the KCP 1.1 incremental-compilation work. Until it's fixed, a DSL
> stress-test run **without** the clean step is not a valid result.

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
