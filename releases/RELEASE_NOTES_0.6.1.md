# Koin Compiler Plugin 0.6.1

_Release date: 2026-03-30_

## New Features

### `module<T>()` and `modules(vararg KClass)` APIs

Load `@Module` classes without referencing generated code directly. The compiler plugin intercepts these calls and transforms them at compile time. ([#14](https://github.com/InsertKoinIO/koin-compiler-plugin/issues/14))

```kotlin
startKoin {
    module<NetworkModule>()
    modules(DataModule::class, CacheModule::class)
}
```

> Requires Koin 4.2.1-beta-1+

### `@ScopeId` Parameter Support

Resolve dependencies from named Koin scopes. Generates `getScope("id").get<T>()`. Compile safety skips `@ScopeId` parameters. ([#2](https://github.com/InsertKoinIO/koin-compiler-plugin/issues/2))

```kotlin
@Factory
class ProfileService(@ScopeId(name = "user_session") val session: UserSession)
// Generates: ProfileService(scope.getScope("user_session").get())
```

### `@Provided` on Parameters

Previously only worked on classes. Now also works on individual constructor parameters to skip compile safety for that specific parameter. ([#7](https://github.com/InsertKoinIO/koin-compiler-plugin/issues/7))

```kotlin
@Singleton
class MyService(@Provided val ctx: PlatformContext)
```

### `Scope` Parameter Injection

Parameters of type `org.koin.core.scope.Scope` are automatically injected with the scope receiver. No annotation needed.

```kotlin
@Scoped
class ScopedService(val scope: Scope)
// Generates: ScopedService(scope)
```

### `@Property`/`@PropertyValue` Validation

Warns at compile time when `@Property("key")` has no matching `@PropertyValue("key")` default.

```kotlin
@PropertyValue("api.timeout")
val defaultTimeout = 30

@Factory
class ApiClient(@Property("api.timeout") val timeout: Int)  // OK

@Factory
class Other(@Property("missing.key") val value: String)     // WARNING
```

## DSL Compile Safety Improvements

- **Module reachability validation** — Tracks which DSL modules are loaded via `modules()` and `includes()`. Reports compile errors for definitions in unreachable modules.
- **`bind()` operator support** — Explicit `bind(Interface::class)` is now tracked for DSL definitions. Auto-binding of supertypes removed for DSL path (matches Koin runtime behavior).
- **`create(::function)` hints** — Provider-only definitions from `create(::function)` now generate cross-module hints with `providerOnly` flag.
- **Qualifier propagation in DSL hints** — `@Named`, `@Qualifier`, and type qualifiers are now encoded in DSL cross-module hints.
- **Call-site detection** — `by inject()` and `by viewModel()` property delegates in class bodies are now detected for A4 validation.

## Bug Fixes

- Fix qualifier propagation in function definition calls
- Fix `@Monitor` tracing: warn if Kotzilla SDK library is missing
- Fix DSL `bind()` not being tracked — removing `bind` now correctly triggers compile error
- Fix `create(::function)` not producing cross-module DSL hints
- Fix FIR module data null for external library types (e.g., DataStore, CoroutineDispatcher)
- Fix cross-module qualifier encoding with dots in names

## Breaking Changes

- **DSL auto-binding removed** — DSL definitions (`single<T>()`, `factory<T>()`) no longer auto-bind to supertypes. Use explicit `bind(Interface::class)` to register secondary types. This matches Koin runtime behavior.

## Compatibility

| Dependency | Version |
|------------|---------|
| **Koin** | 4.2.1-beta-1+ (for `module<T>()` API), 4.2.0-RC2+ (for other features) |
| **Kotlin** | 2.3.x+ (K2 compiler required) |

## Resolved Issues

- [#2](https://github.com/InsertKoinIO/koin-compiler-plugin/issues/2) — `@ScopeId` unrecognized
- [#7](https://github.com/InsertKoinIO/koin-compiler-plugin/issues/7) — `@Provided` still flagging missing dependencies
- [#14](https://github.com/InsertKoinIO/koin-compiler-plugin/issues/14) — Generated `.module()` extension not recognized by IntelliJ (mitigated with `module<T>()` API)
