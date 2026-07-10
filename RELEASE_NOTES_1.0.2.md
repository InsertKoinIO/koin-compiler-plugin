A correctness-focused maintenance release: it removes several **false compile-safety errors** in multi-module projects, fixes **duplicate-hint KLIB failures** on iOS/Native/WASM-JS, and hardens per-compilation state under parallel Gradle daemons.

## 🔑 Highlights

- **No more false "missing dependency" errors across modules** — a `@Module` whose dependency is provided by a *sibling* module (assembled only at `@KoinApplication` / `startKoin`) no longer fails with `KOIN-D001`. It defers to the entry-point graph, and surfaces a new **`KOIN-W002`** warning only when no complete closure is visible in the compilation (#51).
- **iOS / Native / WASM-JS build reliability** — cross-module `@ComponentScan` and cross-module top-level `@Single` functions no longer emit duplicate hint declarations that broke KLIB serialization (#62).
- **Fewer false compile-safety errors on DSL code** — typed DSL definitions with custom lambdas, indirect `parametersOf` helpers, and outer DSL qualifiers are now understood (#36, #49, #61, #41).
- **Compose entry point validated** — `KoinApplication(configuration = koinConfiguration { … })` now runs full-graph safety (#38).

## 🐛 Fixes

### False `KOIN-D001` for cross-module (sibling) dependencies — #51 (KTZ-4256)
In a layered multi-module build, a `@Module` is compiled without visibility of the sibling modules a downstream `@KoinApplication(modules = […])` assembles alongside it. Per-module (A2) validation therefore reported a provider that lives in a sibling as a hard **`KOIN-D001` missing dependency**. Validation now **defers** an unresolved binding when a provider hint for the type exists elsewhere on the build graph, settling it authoritatively at the entry-point closure (A3) or at runtime `checkModules()`. When no complete closure is present in the compilation (e.g. a leaf library module), it emits the new **`KOIN-W002`** warning instead of an error.

> **Scope:** this *narrows* the false positive to the common shape (provider is a compile dependency, or compiled alongside the consumer). A genuine missing dependency with no provider hint anywhere is still a hard `KOIN-D001`. A provider that lives in a non-dependency peer module (type declared in a shared module) is not yet distinguishable at the leaf and may still report — full A2 relaxation is planned for 1.1.

### Duplicate hint declarations broke iOS / Native / WASM-JS — #62 (KTZ-4365)
A cross-module `@ComponentScan` covering a dependency module's package, and cross-module top-level `@Single` functions, could register the same definition more than once — emitting duplicate `componentscan_*` / `definition_function_*` hint declarations. The JVM/DEX toolchain tolerated it (a D8 *"multiple definitions"* warning); KLIB serialization (iOS/Native/WASM-JS) rejected it with a hard **`SignatureClashDetector`** error. Definitions are now de-duplicated by class identity (and by type+qualifier for functions), so each is emitted exactly once per target.

### False `KOIN-D001` for typed DSL definitions with non-`create` lambdas — #36, #49
`single<T> { existingInstance }`, `single<T> { provideX() }`, `viewModel { VM() }` and similar shapes are now recognized as providing `T`, so compile-safety no longer reports `T` as a missing definition. The user's lambda is left untouched.

### False `KOIN-D006` for indirect `parametersOf` helpers — #61
A call site passing an opaque params lambda (e.g. `{ buildParams() }`) no longer triggers `KOIN-D006` ("forgot `parametersOf`"). The diagnostic now fires only when *no* params lambda is present at all.

### Qualifier lost on DSL `create` definitions — #41
An outer DSL qualifier (`single<T>(named("x")) { create(::T) }`) is now propagated into the compile-safety hints, so qualified cross-module definitions resolve correctly instead of producing spurious mismatches.

### Compose `koinConfiguration { }` entry point not validated — #38
`KoinApplication(configuration = koinConfiguration { modules(…) })` is now recognized as a Koin entry point, enabling full-graph (A3) compile-safety for Compose apps. The `koinConfiguration` call is only marked as an entry point — it is not rewritten, so runtime behavior is unchanged.

### Flaky / order-dependent behavior under parallel Gradle daemons — (KTZ-4414)
Plugin config flags and the `@PropertyValue` registry were held in process-global mutable state shared across every compilation in a Gradle daemon. Parallel or interleaved compilations could read another build's flags or have a `@PropertyValue` default dropped. State is now held per-compilation (thread-local, rebound onto the IR phase), matching the existing per-compilation message-collector handling.

## ⚠️ Behavior change — auto-binding excludes framework/marker supertypes — #43, #64

**This changes generated code and can affect runtime resolution.** Auto-detected bindings no longer include framework plumbing / marker supertypes: `kotlin.Any`, `org.koin.core.component.KoinComponent`, `KoinScopeComponent`, and `androidx.lifecycle.ViewModel` / `AndroidViewModel`. Previously a `@KoinViewModel` / `@Single` class implementing one of these could be auto-bound to the framework base type, letting `get<ViewModel>()` / `get<KoinComponent>()` resolve to an arbitrary component (silent wrong-instance resolution). A definition is now registered under its own type and its genuine domain interfaces only.

**Explicit bindings are unaffected** — `@Single(binds = [ViewModel::class])` still binds exactly what you list. If you relied on auto-binding to one of the excluded supertypes, add it explicitly with `binds = [...]`.

## ✅ Compatibility

| | Kotlin 2.3.20 | Kotlin 2.4.0 |
|---|---|---|
| JVM / Android | ✅ | ✅ |
| iOS / Native | ✅ | ✅ |
| WASM/JS — DSL | ✅ | ✅ |
| WASM/JS — annotations | ⚠️ KT-82395 | ✅ |

- **Koin**: 4.2.0+

## 📦 Install

```kotlin
plugins {
    id("io.insert-koin.compiler.plugin") version "1.0.2"
}
```

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.0.1...1.0.2
