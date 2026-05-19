# Koin Playground Apps

Production-quality reference applications demonstrating Koin dependency injection with the Koin Compiler Plugin.

Two identical multi-module Android apps — one using **Annotations**, one using the **Safe DSL** — so you can compare approaches side by side.

## Apps

| App | DI Approach | Key Patterns |
|-----|------------|--------------|
| `app-annotations/` | `@Singleton`, `@Module`, `@ComponentScan`, `@Configuration`, `@KoinApplication` | Annotation-driven with auto-discovery |
| `app-dsl/` | `single<T>()`, `factory<T>()`, `viewModel<T>()`, `create(::fn)`, `bind` | DSL with explicit module composition |

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

- **Koin** 4.2 + **Compiler Plugin** 1.0.0
- Kotlin 2.3.20 (K2)
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
