# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Koin Compiler Plugin

A native Kotlin Compiler Plugin for Koin dependency injection. Transforms `single<T>()` DSL calls and processes `@Singleton`/`@Factory` annotations at compile-time.

Cross-agent essentials (build/test, change rules, Koin terminology) are in [AGENTS.md](AGENTS.md); this file holds the deeper contributor notes.

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
├── koin-compiler-version-adapter/  # Per-Kotlin-line adapters (kotlin-2.3.20/, kotlin-2.4.0/), see Compatibility
├── test-apps/                      # Separate Gradle project for testing
│   ├── sample-app/                 # KMP sample application
│   └── sample-feature-module/      # Multi-module test
├── playground-apps/                # Compile-safety stress-test apps (PR Guard #3)
├── tools/abi-check/                # check-kotlin-abi.sh — gates a new Kotlin version
├── tools/ic-check/                 # check-hint-ic.sh — hint files under real Kotlin IC (harness can't)
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

**Entry points and shared state**

| File | Purpose |
|------|---------|
| `KoinPluginComponentRegistrar.kt` | Compiler plugin entry point; also hosts `KoinPluginLogger` |
| `KoinCommandLineProcessor.kt` | Compiler options (`koin.*` keys) passed from the Gradle plugin |
| `KoinPluginRegistrar.kt` | FIR extension registrar |
| `KoinDiagnostic.kt` | Catalog of `KOIN-…` diagnostics (D/W/A/S/P/M families); codes are stable API |
| `KoinPluginConstants.kt` | Shared constants (definition types, hint prefixes, option keys) |
| `KoinAnnotationFqNames.kt` | Centralized registry of all annotation FQNames |
| `PropertyValueRegistry.kt` | Stores `@PropertyValue` defaults for property injection |
| `ProvidedTypeRegistry.kt` | Stores `@Provided` types (skipped during safety validation) |
| `GeneratedResolutionCallRegistry.kt` | `get()`/`inject()` calls the plugin generated itself, so call-site validation skips them |

**FIR** (`fir/`)

| File | Purpose |
|------|---------|
| `KoinModuleFirGenerator.kt` | Generates `fun T.module()` extensions and the FIR-declared hints (annotation definitions, qualifiers); emits D007 |
| `FirKoinLookupRecorder.kt` | Records IC lookups from `@KoinApplication` to the `@Configuration` modules it discovers via hints |

**IR codegen** (`ir/`)

| File | Purpose |
|------|---------|
| `KoinIrExtension.kt` | IR entry point, runs the pipeline below |
| `KoinDSLTransformer.kt` | Transforms `single<T>()`, `factory<T>()`, `viewModel<T>()`, `worker<T>()`, `scoped<T>()`, `create(::T)`; collects DSL defs, call sites, `includes()` edges |
| `KoinStartTransformer.kt` | Transforms `startKoin<T>()`, `koinApplication<T>()`, `koinConfiguration<T>()`, `withConfiguration<T>()`, `module<T>()`, `modules(vararg KClass)`; emits W003 |
| `KoinAnnotationProcessor.kt` | Processes `@Module`, `@ComponentScan`, `@Singleton`, `@PropertyValue`, `@Provided` annotations |
| `DefinitionCallBuilder.kt` | Builds every definition, `bind` and `includes` IR call; emits A001/A002 |
| `KoinArgumentGenerator.kt` | Builds constructor arguments (`get()`, `getOrNull()`, `inject()`, `getProperty()`…) |
| `LambdaBuilder.kt` | Creates lambda expressions with proper scope/parameter handling |
| `ScopeBlockBuilder.kt` | Builds `scope { }` DSL blocks |
| `QualifierExtractor.kt` | Extracts qualifier annotations (`@Named`, `@Qualifier`) |
| `ModuleFunctionResolver.kt` | Resolves `module()` extensions for `@Module` classes, cross-module included |
| `KoinMonitorTransformer.kt` | Wraps `@Monitor` functions with Kotzilla trace calls |

**Compile safety** (`ir/`) — design in [docs/COMPILE_TIME_SAFETY.md](docs/COMPILE_TIME_SAFETY.md)

| File | Purpose |
|------|---------|
| `ParameterAnalyzer.kt` | Derives each definition's requirements; must mirror `KoinArgumentGenerator` or safety drifts from codegen |
| `BindingRegistry.kt` | Matching engine: provided types vs requirements → D001, D004 (cycles), P001 |
| `CompileSafetyValidator.kt` | Full-graph check for typed entry points (`startKoin<T>()`, `@KoinApplication`), called from `KoinStartTransformer`; W008 |
| `CallSiteValidator.kt` | Call sites (`get<T>()`, `inject<T>()`, `koinViewModel<T>()`…) → D002/D003, `parametersOf` shape → D005/D006, W001/W003/W009/W010; and `validateDslDefinitionGraph` — the DSL-graph D001/D004 path when no typed entry point exists |

**Cross-module hints** (`ir/`) — also drive `@Configuration` discovery, so they run even with `compileSafety = false`

| File | Purpose |
|------|---------|
| `KoinHintTransformer.kt` | Fills bodies of hint functions declared in FIR |
| `DslHintGenerator.kt` | Batched per-module DSL hints (`koin_dsl_hints_<module>.kt` on JVM) + includes-edge relay; emits D008 |
| `InjectedParamHintGenerator.kt` | `@InjectedParam` shape hints that carry D005/D006 across modules |
| `HintFiles.kt` | Where IR-built hints land: on Native/JS/wasm they attach to a FIR anchor file or KLIB drops them (KT-79565, #105) |
| `IncrementalTracking.kt` | `LookupTracker`/`ExpectActualTracker` wiring for Kotlin IC |

### IR pipeline (`KoinIrExtension.generateInternal`, one pass each, in order)

1. `KoinHintTransformer` — hint function bodies
2. `KoinAnnotationProcessor` — collect annotated definitions, generate `@Module` extensions
3. `KoinDSLTransformer` — rewrite DSL calls; collect `dslDefinitions`, call sites, `includes()` edges
4. `DslHintGenerator` — emit/relay DSL hints for downstream modules
5. `KoinStartTransformer` — entry points; full-graph safety via `CompileSafetyValidator`
6. `CallSiteValidator.validateDslDefinitionGraph` — only when DSL defs exist and no typed entry point assembled a graph
7. `KoinMonitorTransformer`, then the `aiAssist` hint if any diagnostic fired

Cross-module data travels only through hint functions (see [docs/PLUGIN_HINTS.md](docs/PLUGIN_HINTS.md)) — custom `@Qualifier` meta-annotations are not preserved in Kotlin metadata, so anything a downstream module must see needs a hint.

### Gradle Plugin (`koin-compiler-gradle-plugin/src/`)

| File | Purpose |
|------|---------|
| `KoinGradlePlugin.kt` | Gradle plugin entry point, registers compiler plugin |
| `KoinGradleExtension.kt` | Configuration DSL (`koinCompiler { }`) |
| `HiddenKoinDependencies.kt` | `check…KoinHiddenDependencies` task: Koin hints reachable only via the runtime classpath of an entry module (JVM/Android) |

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
| `singleOf(::T)` | `Module` | Singleton via constructor/function reference (Constructor DSL, `org.koin.core.module.dsl`) |
| `factoryOf(::T)` | `Module` | Factory via constructor/function reference |
| `scopedOf(::T)` | `ScopeDSL` | Scoped via constructor/function reference |
| `viewModelOf(::T)` | `Module` | ViewModel via constructor/function reference |
| `new(::T)` | `Scope` | Create instance in scope via function reference (`org.koin.core.module.dsl`); real Koin runtime function, left untransformed — only requirement validation applies |

## Annotations

### Definition Annotations

These annotations can be applied to **classes**, **objects**, **functions inside @Module classes**, or **top-level functions**. An annotated `object` resolves to its singleton `INSTANCE` (no constructor call).

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
./test.sh --tests "*JvmBoxTestGenerated*Safety*"  # One generated suite/dir
./test.sh -Pupdate.testdata=true    # Update golden files
./gradlew build --continue          # What CI runs (JDK 21) — includes detekt via `check`

# Lint (detekt + ktlint rules, config in config/detekt/)
./gradlew detekt                    # Must stay green; existing findings parked in config/detekt/baseline/

# Run sample app (from test-apps/)
cd test-apps
./gradlew :sample-app:jvmRun
```

**Lint rules.** Fix new detekt findings in the code; never add them to a baseline. Regenerate a baseline (`./gradlew detektBaselineMain detektBaselineTest detektBaselineTestFixtures`) only to drop findings you fixed. `-PdetektAutoCorrect` also rewrites code that is already baselined (verified: 14 unrelated lines in `KoinGradlePlugin.kt`), so scope it to one module and keep only your own hunks. `testData/` is never linted or reformatted — golden files pin its source positions.

## Test Structure

```
koin-compiler-plugin/
├── testData/
│   ├── box/               # Runtime tests (return "OK" or "FAIL"), golden *.fir.txt + *.fir.ir.txt
│   │   ├── dsl/           # DSL transformations (single<T>, factory<T>, etc.)
│   │   ├── annotations/   # @Singleton, @Factory
│   │   ├── qualifiers/    # @Named, @Qualifier
│   │   ├── params/        # @InjectedParam, @Property, Lazy<T>
│   │   ├── modules/       # @Module, @ComponentScan
│   │   ├── startkoin/     # startKoin, koinApplication
│   │   ├── scopes/        # @Scoped, @Scope
│   │   ├── toplevel/      # Top-level function definitions
│   │   ├── bindings/      # Interface auto-binding
│   │   ├── safety/        # Compile safety that must still compile and run (largest suite)
│   │   └── monitor/       # @Monitor (Kotzilla SDK)
│   ├── diagnostics/       # Single-module KOIN-Dxxx/Wxxx tests: *.errors.txt + *.fir.txt, both asserted
│   ├── crossmodule/       # Multi-module diagnostics (`// MODULE:` blocks): only *.errors.txt is asserted
│   └── registry/          # Input for KoinIrExtensionRegistryReleaseTest / KoinCompilationRetentionTest (not generated)
├── test/                  # Plain JUnit tests (registries, constants, retention/leak guards)
├── test-fixtures/         # Runners, GenerateTests.kt (testData dir → runner), services/KoinTestDirectives.kt
└── test-gen/              # Generated test classes, committed — don't edit by hand
```

Test classes are generated: `generateTests` (runs before `compileTestKotlin` when `testData/` changed, every time under `./test.sh`'s `--rerun-tasks`) writes `JvmBoxTestGenerated.java` (one nested class per `box/` dir), `JvmDiagnosticTestGenerated` and `JvmErrorMessageTestGenerated` into `test-gen/`. A new `.kt` in `testData/` is picked up automatically — commit the regenerated `test-gen/` files with it. Test-only directives (e.g. `DISABLE_COMPILE_SAFETY`) live in `test-fixtures/.../services/KoinTestDirectives.kt`.

**Where a new test goes**: wrong/missing codegen → `box/<area>/`. A diagnostic that should (or should not) fire → `diagnostics/`, or `crossmodule/` when it needs more than one Gradle module. Name false-positive regressions `*_no_false_dNNN`.

**Proving RED for a diagnostic test**: the error-message runner passes when nothing fires and no `.errors.txt` exists, and writes whatever fires into a missing golden. So for a *missing*-diagnostic bug, hand-write the expected `.errors.txt` first; for a *false-positive* bug, commit an empty `.errors.txt` first. Otherwise the test is green on unfixed code, or bakes the bug into the golden.

## PR Guards — check on EVERY pull request

Before opening (or approving) any PR, guards 1 and 2 must pass; guard 3 gates every published version.

### 1. Don't break the current API
- **Plugin configuration surface** (`koinCompiler { }` options), **supported DSL functions**, and **annotation semantics** are public API — no removals or behavior changes without a deprecation/migration path.
- **Generated-code behavior counts as API**: a change in what the plugin emits (binding selection, qualifier matching, default-value handling, eager creation) can silently change runtime behavior of every consumer app. Any intentional behavior change must be called out explicitly in the PR description and release notes.
- Golden-file diffs (`*.fir.txt`, `*.fir.ir.txt`) are the review surface for this: an unexpected golden-file change in an unrelated test = a regression, not noise. Never blanket-regenerate golden files to make CI green.

### 2. New tests for every new case or fixed use-case
- **Bug fix** → a test that reproduces the issue and fails before the fix: a box test in `testData/box/<area>/` (returns "OK" after), or a `diagnostics/`/`crossmodule/` test for a wrong or missing diagnostic (see Test Structure).
- **New transformation/case** → box test(s) + golden files (`./test.sh -Pupdate.testdata=true`) covering the new shape, including qualifier/nullable/default-value variants where relevant.
- **Codegen changes: JVM-green is not done.** Duplicate-signature and KLIB errors are invisible on JVM — compile `test-apps` for at least `iosArm64` (or another native target) and `wasmJs` before declaring a codegen change complete: `./install.sh && cd test-apps && ./gradlew :sample-app:compileKotlinIosArm64 :sample-app:compileKotlinWasmJs :sample-app:compileKotlinJs :sample-app:jvmTest`. `test-apps` uses the repo's own `pluginVersion` (override with `-PkoinPluginVersion=…`) and Kotlin 2.4.20 — JS/wasm with `@ComponentScan` can't compile on 2.3.20 (KT-82395).

### 3. Compile-safety stress test — run for EVERY published version
- Run the full numbered checklist (scenarios 0–4: baseline, removed definition, transitive-includes edge, module dropped from root's list, fully-emptied module) against **both** `playground-apps/app-annotations` and `app-dsl`. Full procedure, exact file:line targets, and expected diagnostics: [`playground-apps/README.md`](playground-apps/README.md#compile-safety-stress-test-run-for-every-plugin-version).
- Open incremental-build gaps — a plain build can false-green, so follow the README's command for each:
  - **Scenario 1 on `app-annotations`**: needs `--rerun-tasks` (`:app` validates against a stale `@ComponentScan` hint). `app-dsl` must fail on a plain build.
  - **Scenario 4 (module emptied entirely) on `app-dsl`**: only a full `clean` + `--no-build-cache` catches it. Currently caught incrementally on `app-annotations`, but unexplained — re-verify on both apps each release.

## Release

```bash
# Release to Maven Central
./release-to-central.sh

# Release to Gradle Plugin Portal
./release-to-gradle-portal.sh
```

**Release notes — concise, written for Koin users.** Target ~300 words. One opening sentence saying what the release is for, then Added / Fixed / Changed / Compatibility / Install. Each bullet states what a user sees (the diagnostic, the crash, the false positive) and the fix, never the internal mechanism (hint carriers, relays, adapters, builder APIs). Keep contributor credits and issue links. Behavior changes still get their own explicit entry.

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
    strictSafety = true        // Force safety pass to bypass Kotlin IC on this module (default: auto-detect; explicit `false` wins, with a warning)
    aiAssist = true            // One hint per compilation pointing to the Kotzilla MCP when a Koin diagnostic fires (default: true)
    logSeverity = "warning"           // Severity of informational output (default: "warning"; set "info" under allWarningsAsErrors)
    versionCheckSeverity = "warning"  // Severity of the Kotlin-version-compatibility warning (default: "warning"; independent of logSeverity)
    hiddenDependencyCheck = "none"    // Opt-in: entry module reaches Koin definitions only via the runtime classpath: "warning", "error", "none" (default)
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

- **Auto-enabled** on any module whose source contains `startKoin`, `koinApplication` or `@KoinApplication` (the aggregator): its `compileKotlin` re-runs every build, because Kotlin IC can't see DSL lambda-body changes or new `@ComponentScan` files. Library and feature modules stay incremental.
- **Explicit value wins (1.2.2)**: `strictSafety = false` turns it off even on a detected aggregator, with a warning (incremental builds may miss graph changes; clean and CI builds still validate). `strictSafetyForceOff` is deprecated (needed 1.1.0–1.2.1, when a plain `false` was ignored). Detection scans what the compile task compiles (`src/<set>/java`, AGP 9 built-in Kotlin); KMP compilations scan only their own source sets, not `commonMain`.
- **Detection is text-based and narrower than the compiler's entry points**: Ktor `install(Koin) { }`, Compose `KoinApplication(configuration = koinConfiguration { })` and `withConfiguration` aggregators are not auto-detected — set `strictSafety = true` there.
- No effect when `compileSafety = false`.

Full rationale and the IC limits it works around: [docs/COMPILE_TIME_SAFETY.md](docs/COMPILE_TIME_SAFETY.md#strict-safety-incremental-compilation-bypass), GH #32.

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
- **Kotlin**: K2 compiler required. One artifact spans **Kotlin 2.3.20 → 2.4.20** via `koin-compiler-version-adapter` (per-line adapter classes, selected at plugin load — see `KotlinAdapterLoader`). Verified versions live in `supported-kotlin-versions.txt` and the adapter registry, which are the same list.

**Version-gate policy** (the plugin binds to unstable compiler internals — every Kotlin minor is a potential break):

- **Known-broken Kotlin version** (below the registered floor) → fail fast with a clear diagnostic naming the version, the supported range, and the tracking issue — never let a raw `ClassCastException`/`NoSuchMethodError` surface as the error.
- **Any version that is not a verified entry** (a future minor line like 2.5.0, or an unverified patch inside a registered line) → WARN + proceed with the highest adapter at or below it, never hard-block.
- **A new patch within an already-registered minor line is NOT trusted** (changed in 1.2.1). Trust is per exact `major.minor.patch`: a version reuses an adapter *class* by line, but only counts as verified when it has its own registry entry, earned by a green `tools/abi-check/check-kotlin-abi.sh <version>`. Anything else warns.
  **Why**: 1.2.0 relaxed this to `major.minor` on the strength of 2.4.10, and Kotlin 2.4.20 falsified it three weeks later — 4 removed/re-signed compiler APIs, a raw `NoSuchMethodError` from `DslHintGenerator`, and the relax had silenced the only warning that would have hinted at it (GH #89, #99). Kotlin ships **feature releases in the `.20` patch slot**; this plugin's own floor, 2.3.20, is an x.y.20. `major.minor` is not a safe trust unit.
  A new version needing no adapter change just points at the existing class (2.4.10 and 2.4.20 both map to `Kotlin240Adapter`) — a registry line, not a new module. `check-kotlin-abi.sh` verifies the core **and** the adapter class that version selects.
- Keep `supported-kotlin-versions.txt`, the adapter registry, and the README in sync on every release.
