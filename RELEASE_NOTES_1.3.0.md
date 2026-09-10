A startup-performance release. The generated code for annotation-discovered definitions changes
shape: it now registers each definition directly against the Koin registry with an index key
computed at compile time, instead of going through the runtime DSL. Resolution is identical; what
disappears is per-definition work Koin used to do at module load.

Ships alongside Koin `4.2.3` (see the koin-core release notes for the runtime side of KTZ-4829).
Runs on every Koin version the plugin supports (4.2.0+) — nothing new is required from koin-core.

## ⚡ Changed: direct registration for `@Single` / `@Singleton` / `@Factory` / `@Scoped`

Before, for each annotated class or function, the generated `module()` emitted:

```kotlin
buildSingle(UserRepository::class, null, { UserRepository(get()) }).bind(Repository::class)
```

At startup that call built the Koin index key String (`"pkg.UserRepository::_root_"`) with
`indexKey()`, allocated a `KoinDefinition` wrapper, and — for each `bind` — re-indexed every type
bound so far. Now the plugin emits:

```kotlin
val def = BeanDefinition(rootScopeQualifier, UserRepository::class, null, { UserRepository(get()) }, Kind.Singleton, listOf(Repository::class), null)
val factory = SingleInstanceFactory(def)
saveMapping("pkg.UserRepository::_root_", factory)
saveMapping("pkg.Repository::_root_", factory)
```

The keys are the exact Strings `indexKey()` would have built, computed once at compile time with
the class name derived the way `KoinPlatformTools.getClassName` derives it on each target:
`KClass.java.name` on JVM/Android (`pkg.Outer$Inner`), `qualifiedName` on Native, `simpleName` on
JS/Wasm.

**What is measured** — `perf-apps/startup-bench` (`./benchmark.sh`), a Metro-shaped workload at
10% scale (50 modules, 276 definitions, 87 edges, 180 multibinding contributions), JMH 1 fork,
5+5 × 1 s, Apple M1 Max, koin-core 4.2.3-Beta1, plugin 1.2.1 vs 1.3.0:

| | 1.2.1 | 1.3.0 | Δ |
|---|---|---|---|
| Module loading (`koinApplication<App> {}`) | 0.062 ms | 0.037 ms | **−39%** |
| Loading + realizing every multibinding (`getAll`) | 0.129 ms | 0.092 ms | **−28%** |
| `getAll` scan on a built container | 0.005 ms | 0.005 ms | 0 |

Against koin-core 4.2.2 the same change is −16% on loading (the rest of the gap belongs to the
runtime work shipped in 4.2.3). Full-scale (2,756 definitions) and Metro-harness numbers to follow.

### The fallback rule

A definition is registered directly only when the plugin is certain of its key. Otherwise it keeps
the previous `buildSingle`/`bind` call, so a mixed module is normal and correct:

- `kotlin.*` / `java.*` types — `String::class.java.name` is `java.lang.String`, not derivable from
  the Kotlin name. A `@Singleton fun provideUrl(): String` keeps the DSL call.
- Local classes, unknown platforms (common metadata compilation).
- Archetype scopes (`viewModelScope`, `activityScope`, `activityRetainedScope`, `fragmentScope`):
  their scope qualifier comes from a satellite artifact at runtime.
- `@KoinViewModel` / `@KoinWorker`: their DSL lives in `koin-core-viewmodel` /
  `koin-android-workmanager` and has behavior of its own.
- Any koin-core symbol the plugin cannot resolve on the classpath.

### Two new options

```kotlin
koinCompiler {
    directRegistration = true   // default; false restores the 1.2.x generated shape
    verifyGeneratedKeys = false // default; true re-checks every key against indexKey() at module load
}
```

`directRegistration = false` is the kill switch. `verifyGeneratedKeys = true` makes every directly
registered definition compare its compile-time key with `indexKey()` when the module loads and throw
on a mismatch — it re-introduces the String build the feature removes, so it is for tests and
troubleshooting, not production. The plugin's own box tests run with it on.

### How it was verified

- All plugin tests pass with `verifyGeneratedKeys` enabled, so the 52 annotation-path fixtures
  (bindings, string and type qualifiers, typed and named scopes, module and top-level functions,
  `createdAtStart`) had every emitted key checked against `indexKey()` at runtime, on JVM, against
  published Koin 4.2.1.
- New fixtures `testData/box/directregistration/`: nested classes in a package, a nested type
  qualifier, a nested scope class, `createdAtStart` combined with a binding, a function definition
  returning a nested type, and a `kotlin.CharSequence` binding taking the fallback. `kill_switch.kt`
  pins the `directRegistration = false` shape.
- Off-JVM: `playground-apps/app-kmp-klib` now has a `main()` that starts Koin and resolves every
  definition with `verifyGeneratedKeys = true`. It prints `KOIN_DIRECT_REGISTRATION_OK` on
  macosArm64 and wasmJs (Node); iosArm64 compiles. KLIB serialization is unaffected.

Golden files: 52 `.fir.ir.txt` dumps changed — registration blocks only. No frontend (`.fir.txt`) or
diagnostics golden moved.

## Compatibility

- Kotlin: unchanged from 1.2.1 (2.3.20 → 2.4.20, verified list in `supported-kotlin-versions.txt`).
- Koin: 4.2.0+. `Module.saveMapping`, the `BeanDefinition` constructor and the three
  `InstanceFactory` constructors used by the generated code are all part of koin-core's binary
  surface since 4.2.0.
