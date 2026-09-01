# Koin Compiler Plugin

A native Kotlin Compiler Plugin for Koin dependency injection. Transforms `single<T>()` DSL calls and processes `@Singleton`/`@Factory` annotations at compile-time.

## Doctrine

- **Evidence-based claims.** "fixed/verified/done" must cite file:line, test output, or a box-test run — never "looks right + tests pass".
- **Falsify, don't confirm.** A box test must try to BREAK the transformation. Prove RED before GREEN: a regression test must fail on the unfixed code.
- **Silent is worse than broken.** Dropping an annotation value, skipping a definition, or binding an unexpected type *without a diagnostic* is the worst failure class for a compiler plugin — when in doubt, emit an error/warning rather than degrade silently.

> **SURPRISE RULE — mandatory.** If the project surprises you or contradicts these docs, STOP, tell the user, and record it here. "Surprising" = not inferable from the code in one grep.

## Project Structure

```
koin-compiler-plugin/
├── koin-compiler-plugin/           # Compiler plugin (FIR + IR phases)
├── koin-compiler-gradle-plugin/    # Gradle plugin for easy integration
├── test-apps/                      # Separate Gradle project for testing
│   ├── sample-app/                 # KMP sample application
│   └── sample-feature-module/      # Multi-module test
└── docs/                           # Documentation
```

## Documentation

- **[docs/MIGRATION_FROM_KSP.md](docs/MIGRATION_FROM_KSP.md)** - Migration from Koin Annotations (KSP)
- **[docs/DEBUGGING.md](docs/DEBUGGING.md)** - Debugging, logging, common issues
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** - Project structure, compilation flow
- **[docs/TRANSFORMATIONS.md](docs/TRANSFORMATIONS.md)** - All transformation examples
- **[docs/COMPILER_BASICS.md](docs/COMPILER_BASICS.md)** - Kotlin compiler plugin fundamentals
- **[docs/PLUGIN_HINTS.md](docs/PLUGIN_HINTS.md)** - Cross-module discovery via hint functions
- **[docs/FIR_PROCESSING.md](docs/FIR_PROCESSING.md)** - FIR deep dive: source types, KMP phases, synthetic files
- **[docs/ROADMAP.md](docs/ROADMAP.md)** - Project status and future plans
- **[docs/COMPILE_TIME_SAFETY.md](docs/COMPILE_TIME_SAFETY.md)** - Dependency validation design
- **[docs/CASE_STUDY_NOW_IN_ANDROID.md](docs/CASE_STUDY_NOW_IN_ANDROID.md)** - Real-world migration case study

## Key Files

### Compiler Plugin (`koin-compiler-plugin/src/org/koin/compiler/plugin/`)

| File | Purpose |
|------|---------|
| `ir/KoinDSLTransformer.kt` | Transforms `single<T>()`, `factory<T>()`, `viewModel<T>()`, `worker<T>()`, `scoped<T>()`, `create(::T)` |
| `ir/KoinStartTransformer.kt` | Transforms `startKoin<T>()`, `koinApplication<T>()`, `koinConfiguration<T>()`, `withConfiguration<T>()`, `module<T>()`, `modules(vararg KClass)` |
| `ir/KoinAnnotationProcessor.kt` | Processes `@Module`, `@ComponentScan`, `@Singleton`, `@PropertyValue`, `@Provided` annotations |
| `ir/KoinIrExtension.kt` | Plugin entry point, orchestrates IR phases |
| `ir/LambdaBuilder.kt` | Creates lambda expressions with proper scope/parameter handling |
| `ir/ScopeBlockBuilder.kt` | Builds `scope { }` DSL blocks |
| `ir/QualifierExtractor.kt` | Extracts qualifier annotations (`@Named`, `@Qualifier`) |
| `PropertyValueRegistry.kt` | Stores `@PropertyValue` defaults for property injection |
| `ProvidedTypeRegistry.kt` | Stores `@Provided` types (skipped during safety validation) |
| `KoinPluginConstants.kt` | Shared constants (definition types, hint prefixes, option keys) |
| `KoinAnnotationFqNames.kt` | Centralized registry of all annotation FQNames |
| `fir/KoinModuleFirGenerator.kt` | Generates `fun T.module()` extension functions |
| `fir/KoinPluginRegistrar.kt` | FIR extension registrar |

### Gradle Plugin (`koin-compiler-gradle-plugin/src/`)

| File | Purpose |
|------|---------|
| `KoinGradlePlugin.kt` | Gradle plugin entry point, registers compiler plugin |
| `KoinGradleExtension.kt` | Configuration DSL (`koinCompiler { }`) |

## Supported DSL Functions

| Function | Receiver | Description |
|----------|----------|-------------|
| `single<T>()` | `Module` | Singleton using primary constructor |
| `factory<T>()` | `Module` | Factory using primary constructor |
| `viewModel<T>()` | `Module` | ViewModel using primary constructor |
| `worker<T>()` | `Module` | Worker using primary constructor |
| `scoped<T>()` | `ScopeDSL` | Scoped using primary constructor |
| `create(::T)` | `Scope` | Create instance in scope |
| `module<T>()` | `KoinApplication` | Load a single `@Module` class |
| `modules(vararg KClass)` | `KoinApplication` | Load multiple `@Module` classes |
| `singleOf(::T)` | `Module` | Singleton via constructor/function reference (Koin's constructor-shorthand DSL, `org.koin.core.module.dsl`) |
| `factoryOf(::T)` | `Module` | Factory via constructor/function reference |
| `scopedOf(::T)` | `ScopeDSL` | Scoped via constructor/function reference |
| `viewModelOf(::T)` | `Module` | ViewModel via constructor/function reference |
| `new(::T)` | `Scope` | Create instance in scope via function reference (`org.koin.core.module.dsl`); real Koin runtime function, left untransformed — only requirement validation applies |

## Annotations

### Definition Annotations

These annotations can be applied to **classes**, **functions inside @Module classes**, or **top-level functions**.

| Annotation | Effect |
|------------|--------|
| `@Single` / `@Singleton` | Generates `single { }` definition |
| `@Factory` | Generates `factory { }` definition |
| `@Scoped` | Generates `scoped { }` definition |
| `@KoinViewModel` | Generates `viewModel { }` definition |
| `@KoinWorker` | Generates `worker { }` definition |

**Top-level function example:**
```kotlin
// Top-level functions with annotations (discovered by @ComponentScan)
@Singleton
fun provideDatabase(): DatabaseService = PostgresDatabase()

@Factory
fun provideCache(db: DatabaseService): CacheService = RedisCache(db)

@Single
@Named("http")
fun provideHttpClient(): NetworkClient = OkHttpClient()
```

### Module Annotations

| Annotation | Effect |
|------------|--------|
| `@Module` | Marks class as a Koin module container |
| `@ComponentScan("pkg")` | Scans package(s) for annotated classes and top-level functions |
| `@Configuration` | Marks module for auto-discovery (supports labels: `@Configuration("test", "prod")`) |
| `@KoinApplication(modules)` | Specifies modules for `startKoin<T>()` |

### Parameter Annotations

| Annotation | Effect |
|------------|--------|
| `@Named("x")` | String qualifier → `named("x")` |
| `@Qualifier(name = "x")` | String qualifier → `named("x")` |
| `@Qualifier(MyType::class)` | Type qualifier → `typeQualifier<MyType>()` |
| `@InjectedParam` | Uses `ParametersHolder.get()` |
| `@Property("key")` | Injects property value (warns if no `@PropertyValue` default) |
| `@PropertyValue("key")` | Provides default value for `@Property` |
| `@Provided` | Marks type/parameter as externally available (skips safety validation). **Required for dependencies that arrive via `loadKoinModules(...)`** — compile time cannot prove a runtime-loaded module is loaded before a given resolution, so the plugin does not try; see [docs/COMPILE_TIME_SAFETY.md](docs/COMPILE_TIME_SAFETY.md#runtime-module-loading-loadkoinmodules) |
| `@ScopeId(name = "x")` | Resolves from named scope → `getScope("x").get<T>()` |
| `@ScopeId(MyScope::class)` | Resolves from typed scope → `getScope("fqName").get<T>()` |

## Build Commands

```bash
# Build and install to Maven Local
./install.sh

# Run compiler plugin tests
./test.sh                           # Run all tests
./test.sh --tests "*SingleBasic*"   # Run specific tests
./test.sh -Pupdate.testdata=true    # Update golden files

# Run sample app (from test-apps/)
cd test-apps
./gradlew :sample-app:jvmRun
```

## Test Structure

```
koin-compiler-plugin/testData/
├── box/                    # Runtime tests (return "OK" or "FAIL")
│   ├── dsl/               # DSL transformations (single<T>, factory<T>, etc.)
│   ├── annotations/       # @Singleton, @Factory
│   ├── qualifiers/        # @Named, @Qualifier
│   ├── params/            # @InjectedParam, @Property, Lazy<T>
│   ├── modules/           # @Module, @ComponentScan
│   ├── startkoin/         # startKoin, koinApplication
│   ├── scopes/            # @Scoped, @Scope
│   ├── toplevel/          # Top-level function definitions
│   └── bindings/          # Interface auto-binding
└── diagnostics/           # Compilation error tests (future)
```

Each test file has golden files (`*.fir.txt`, `*.fir.ir.txt`) containing expected compiler output.

## PR Guards — check on EVERY pull request

Before opening (or approving) any PR, both guards must pass:

### 1. Don't break the current API
- **Plugin configuration surface** (`koinCompiler { }` options), **supported DSL functions**, and **annotation semantics** are public API — no removals or behavior changes without a deprecation/migration path.
- **Generated-code behavior counts as API**: a change in what the plugin emits (binding selection, qualifier matching, default-value handling, eager creation) can silently change runtime behavior of every consumer app. Any intentional behavior change must be called out explicitly in the PR description and release notes.
- Golden-file diffs (`*.fir.txt`, `*.fir.ir.txt`) are the review surface for this: an unexpected golden-file change in an unrelated test = a regression, not noise. Never blanket-regenerate golden files to make CI green.

### 2. New tests for every new case or fixed use-case
- **Bug fix** → a box test in `testData/box/<area>/` that reproduces the issue: fails before the fix, returns "OK" after.
- **New transformation/case** → box test(s) + golden files (`./test.sh -Pupdate.testdata=true`) covering the new shape, including qualifier/nullable/default-value variants where relevant.
- **Codegen changes: JVM-green is not done.** Duplicate-signature and KLIB errors are invisible on JVM — compile `test-apps` for at least `iosArm64` (or another native target) and `wasmJs` before declaring a codegen change complete.

### 3. Compile-safety stress test — run for EVERY published version
- Run the full numbered checklist (scenarios 0–4: baseline, removed definition, transitive-includes edge, module dropped from root's list, fully-emptied module) against **both** `playground-apps/app-annotations` and `app-dsl`. Full procedure, exact file:line targets, and expected diagnostics: [`playground-apps/README.md`](playground-apps/README.md#compile-safety-stress-test-run-for-every-plugin-version).
- **Scenario 1 — `app-annotations` needs `--rerun-tasks`, `app-dsl` doesn't (found 2026-08-28, 1.2.0-Beta7, daemon-isolated — real, not a shared-daemon artifact).** Removing `@Singleton` from a class on `app-annotations` (class kept, rest of the module unchanged) can pass a *plain* `./gradlew :app:compileDebugKotlin` silently: the owning module correctly regenerates its `@ComponentScan` hint in the same build (verified: definition count in the hint drops), but `:app`'s own compile task — which also re-executes, this is not a Gradle up-to-date skip — validates against a stale view of it. `--rerun-tasks` (discards Kotlin's own IC cache, not just Gradle's) reliably catches it. Narrow, not systemic: scenarios 2 and 3 on the same app caught their breaks on the first plain build, no rerun needed. Not fixed. Full detail: checklist scenario 1 in the README link above.
- **No clean/rerun needed for DSL removed-definition detection (as of 1.1.0-Beta3).** Incremental DSL rebuilds detect a removed definition on a plain build — `./gradlew :app:compileDebugKotlin` on `app-dsl` MUST fail with `KOIN-D001`. The old DSL orphan-hint false-green (removing a `single<T>()` left an orphaned per-def hint class `…Dsl_singleKt.class` that IC never deleted) is fixed: each module's DSL hints are batched into one `koin_dsl_hints_<module>.kt` regenerated wholesale (same shape the annotation hints always used), so a removed def leaves no orphan.
- **Known limitation — a module that goes COMPLETELY empty** (loses its last `includes()` AND has no definitions): confirmed **still true for `app-dsl`** (2026-08-28, 1.2.0-Beta7) — plain incremental `compileDebugKotlin` passes silently, only a full clean + `--no-build-cache` catches it (the emitted artifact is correct; the residual is K2 re-resolving a changed hint signature inside one IC session). Confirmed **no longer reproducing for `app-annotations`** in the same verification pass — a plain incremental build caught it with no clean needed, contradicting this bullet's previous unconditional claim. Root cause not deliberately investigated; plausibly an incidental side effect of hint-batching/multi-hop `@Configuration` work already on this branch. **Treat the annotation-path fix as observed, not guaranteed — re-verify on both apps before relying on it for a release.** Full procedure + rationale: checklist scenario 4 in the README link above.

## Release

```bash
# Release to Maven Central
./release-to-central.sh

# Release to Gradle Plugin Portal
./release-to-gradle-portal.sh
```

**Release notes — Added vs Fixed:** ask *"would this affect a user on the previous supported Kotlin/Koin range?"* Yes → `Fixed`. No (only enables a new version range) → ONE umbrella `Added` entry with sub-bullets. Intentional generated-code behavior changes always get their own explicit entry.

## Plugin Configuration

```kotlin
// build.gradle.kts
koinCompiler {
    userLogs = true           // Component detection logs
    debugLogs = true          // Internal processing logs (verbose)
    unsafeDslChecks = true    // Validates create() is the only instruction in lambda (default: true)
    skipDefaultValues = true  // Skip injection for parameters with default values (default: true)
    compileSafety = true       // Compile-time dependency validation (default: true)
    strictSafety = true        // Force safety pass to bypass Kotlin IC on this module (default: auto-detect; `false` is ignored once an entry point is detected)
    strictSafetyForceOff = true // Escape hatch: confirms a detected entry point is a misfire, actually disables strictSafety (default: false)
    logSeverity = "warning"           // Severity of informational output (default: "warning"; set "info" under allWarningsAsErrors)
    versionCheckSeverity = "warning"  // Severity of the Kotlin-version-compatibility warning (default: "warning"; independent of logSeverity)
}
```

### Informational output severity (`allWarningsAsErrors` compatibility, #73)

`userLogs`/`debugLogs` messages and `@Monitor` tracing-enabled summaries are emitted at WARNING
severity by default, which fails a build compiled with Gradle's `allWarningsAsErrors` (`-Werror`)
even though this output is purely informational. Set `logSeverity = "info"` to downgrade it —
`INFO` is not treated as a build-failing warning under `-Werror`.

The Kotlin-version-compatibility check ("you're on an unverified Kotlin version") has its own,
**separate** `versionCheckSeverity` setting — deliberately not tied to `logSeverity`, since muting
informational plugin noise shouldn't also hide a real compiler-compatibility risk. Set it to
`"info"` only if you've already assessed that risk and it's blocking your `-Werror` build.

Neither setting affects real diagnostics (`KOIN-Dxxx`/`KOIN-Wxxx`/etc.) — those always report at
their own severity (see [docs/COMPILE_TIME_SAFETY.md](docs/COMPILE_TIME_SAFETY.md)).

### Strict Safety (incremental compilation bypass)

**Auto-enabled by default** on modules that contain `startKoin`, `koinApplication`, or `@KoinApplication`. The Gradle plugin scans source files at configuration time, detects the aggregator, and emits a one-line lifecycle log so the decision is visible.

**Mandatory once detected (1.1.0+).** `strictSafety = false` no longer silently wins on a module where detection fires — the plugin ignores it, forces the safety pass on anyway, and logs a warning explaining why. Full-graph validation no longer has a redundant per-module safety net once A2 stops verifying (see [docs/COMPILE_TIME_SAFETY.md](docs/COMPILE_TIME_SAFETY.md)), so an aggregator silently skipping re-validation on an incremental rebuild is a correctness gap, not a preference. `strictSafety = true` still works everywhere (forces it on regardless of detection).

**Escape hatch for a genuine detector misfire**: if the `startKoin`/`koinApplication`/`@KoinApplication` marker only appears in a comment or string literal (not a real entry point), set `strictSafetyForceOff = true` — a separate, explicit acknowledgement from a plain `strictSafety = false`, since the two cases ("this isn't really an aggregator" vs. "I just don't want the cost") are otherwise indistinguishable from the Gradle side.

**Why**: full-graph safety only runs in the aggregator's `compileKotlin`, and Kotlin's IC — today, in K2 with the Build Tools API path that AGP uses — doesn't give the aggregator a reason to re-run when the graph actually changed. Two places where IC's tracking is too coarse for a DI graph:

- **DSL definitions sit inside `module { … }` lambda bodies.** Lambda bodies aren't part of any declaration's ABI, and IC tracks per-declaration changes. So `single<X>() bind I::class` going away produces no signal the aggregator can see — its references to the module class are still valid, IC marks the task UP-TO-DATE, the removed binding goes unverified.

- **`@ComponentScan` works by package, not by source reference.** Adding `@Singleton class NewService` to a scanned package creates a file nothing in the aggregator's source mentions. IC needs a source-level edge to invalidate downstream consumers, and there isn't one — the aggregator never re-scans, never sees the new class.

We already record file-pair links via `ExpectActualTracker` to plug some of this (same primitive Metro uses for its `@BindingContainer` case), but those links only fire when both files participate in a build, and they don't cover newly-introduced files that nothing pointed to before. Forcing the aggregator to re-run is the smallest correct behavior we can produce on top of what K2 IC tells us today — a workaround framed against IC limitations, not a permanent design statement. If K2's IC tracking later grows package-scope edges or surfaces lambda-body changes through a different signal, this auto-enable becomes redundant and we can revisit.

**Cost** is bounded: only the aggregator's `compileKotlin` task runs every build. Library and feature modules stay fully incremental.

**When auto-detection triggers**:

| Aggregator style | Detected via |
|---|---|
| DSL (`startKoin { modules(...) }`, `koinApplication { … }`) | `startKoin` / `koinApplication` string in source |
| Annotation (`@KoinApplication`) | `@KoinApplication` string in source |

When the auto-detection misfires (e.g. test fixtures referencing `startKoin` in comments, or a non-aggregator helper file with the marker), set `strictSafetyForceOff = true` explicitly to opt out — plain `strictSafety = false` alone is ignored once detection fires (see above).

Has no effect when `compileSafety = false`. See: https://github.com/InsertKoinIO/koin-compiler-plugin/issues/32

### DSL Safety Checks

When `unsafeDslChecks` is enabled (default), the plugin validates that `create(::T)` is the only instruction inside lambdas. This ensures proper dependency injection patterns:

```kotlin
// Valid - create() is the only instruction
scoped { create(::MyService) }

// Invalid - will cause compilation error
scoped {
    println("Creating service")  // Extra statement not allowed
    create(::MyService)
}
```

Set `unsafeDslChecks = false` when migrating from legacy DSL code that has additional statements in create lambdas.

### Skip Default Values

When `skipDefaultValues` is enabled (default), parameters with Kotlin default values will use the default instead of being resolved from the DI container. This applies when:
- The parameter has a default value
- The parameter is **not** nullable (nullable params still use `getOrNull()`)
- The parameter has **no** explicit annotation (`@Named`, `@Qualifier`, `@InjectedParam`, `@Property`)

```kotlin
// With skipDefaultValues = true (default):
class ServiceWithDefault(val name: String = "default_name")
single<ServiceWithDefault>()
// Generated: ServiceWithDefault()  -- uses Kotlin default

// Nullable parameters are still injected:
class Service(val dep: Dependency? = null)
single<Service>()
// Generated: Service(scope.getOrNull())

// Annotated parameters are always injected:
class Service(@Named("custom") val name: String = "fallback")
single<Service>()
// Generated: Service(scope.get(named("custom")))
```

Set `skipDefaultValues = false` to always inject all parameters from the DI container, ignoring Kotlin default values.

## Compatibility — verified range + version gate

- **Koin**: 4.2.0+
- **Kotlin**: K2 compiler required. One artifact spans **Kotlin 2.3.20 → 2.4.x** via `koin-compiler-version-adapter` (per-line adapter classes, selected at plugin load — see `KotlinAdapterLoader`).

**Version-gate policy** (the plugin binds to unstable compiler internals — every Kotlin minor is a potential break):

- **Known-broken Kotlin version** (below the registered floor) → fail fast with a clear diagnostic naming the version, the supported range, and the tracking issue — never let a raw `ClassCastException`/`NoSuchMethodError` surface as the error.
- **Unknown future minor line** (above the newest registered line) → WARN + proceed with the newest adapter, never hard-block.
- **A new patch within an already-registered minor line** (e.g. 2.4.10 vs registered 2.4.0) reuses that adapter silently — no warning. Before relying on this for a new patch, run `tools/abi-check/check-kotlin-abi.sh <version>` to confirm the plugin's compiler-API refs still resolve; a break there means the line-level trust just failed and needs its own adapter entry.
- Keep `supported-kotlin-versions.txt`, the adapter registry, and the README in sync on every release.
