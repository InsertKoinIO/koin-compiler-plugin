# Koin Compiler Plugin - Roadmap

Project status, completed work, and future plans.

## Completed

- [x] Implement reified type parameter syntax `single<T>()`, `factory<T>()`, etc.
- [x] Implement `Scope.create(::T)` constructor reference syntax
- [x] Implement `worker<T>()` DSL for Android WorkManager
- [x] Make `plugin-support` a full KMP library (all Koin targets, requires Koin 4.2.0-beta3+)
- [x] Create stub classes for ViewModel and ListenableWorker (avoid runtime dependencies)
- [ ] Gradle plugin auto-injects `koin-annotations` dependency (needs KMP support)
- [x] Move samples to separate folder for independent Kotlin version
- [x] Add Kotlin 2.2.x workarounds (stdlib for JS/WASM, disable metadata compilation)
- [x] Deprecate `org.koin.android.annotation.KoinViewModel`
- [x] Implement `@Configuration` cross-module discovery via hint functions
- [x] Fix KMP multi-phase FIR compilation (unique synthetic file names per class)
- [x] Fix expect/actual class handling (skip expect classes in FIR)
- [x] Fix K/Native synthetic file generation (skip on Native targets)
- [x] Fix cross-source-set module resolution (fallback via context.referenceFunctions)
- [x] Fix object module support (use irGetObject instead of constructor)
- [x] Add configurable logging (userLogs for component detection, debugLogs for internal processing)
- [x] Implement `koinConfiguration<T>()` DSL transformation
- [x] Implement `KoinApplication.withConfiguration<T>()` extension transformation
- [x] Implement `@PropertyValue` annotation for property defaults
- [x] Support definition annotations on top-level functions (discovered by `@ComponentScan`)

---

## Phase 1: Packaging & Publishing ✅

Prepared for public release on Maven Central.

### 1.1 Build Configuration
- [x] Review and finalize artifact coordinates
- [x] Ensure proper module structure for publishing
- [x] Add LICENSE file (Apache 2.0)
- [x] Add README.md with usage instructions

### 1.2 Maven Central Publishing
- [x] Configure `maven-publish` plugin for all modules
- [x] Set up GPG signing for artifacts
- [x] Configure Sonatype OSSRH credentials
- [x] Add POM metadata (description, license, developers, SCM URLs)
- [x] Publish artifacts: `compiler-plugin`, `gradle-plugin`
  - Note: `plugin-support` is included in Koin (`koin-annotations`), not published separately

### 1.3 Gradle Plugin Portal
- [x] Register plugin ID on Gradle Plugin Portal
- [x] Configure `java-gradle-plugin` publishing
- [x] Test plugin resolution via `plugins { id("io.insert-koin.compiler.plugin") }`

### 1.4 CI/CD & Release Process
- [x] Set up GitHub Actions for CI (build, test)
- [x] Automated release workflow (tag -> publish)
- [x] Version management strategy (semver)

### 1.5 Multi-Kotlin Version Strategy
- Deferred: Will follow latest Kotlin versions and adapt as needed
- No complex versioning scheme required for now

---

## Phase 2: Compile-Time Safety & Monitoring (Current)

Advanced features for production reliability.

### 2.1 `@PropertyValue` Default Values ✅
Support default values for property injection:
- [x] `@PropertyValue("key")` annotation on constant
- [x] Generate `getProperty("key", defaultValue)` calls

```kotlin
@PropertyValue("api.timeout")
val DEFAULT_TIMEOUT = 30000

@Factory
class ApiClient(@Property("api.timeout") val timeout: Int)
// Generates: factory { ApiClient(getProperty("api.timeout", DEFAULT_TIMEOUT)) }
```

### 2.2 Skip Default Values ✅
Skip DI injection for parameters with Kotlin default values:
- [x] `skipDefaultValues` option in `koinCompiler { }` DSL (default: `true`)
- [x] Non-nullable parameters with defaults use Kotlin default value
- [x] Nullable parameters still use `getOrNull()` regardless
- [x] Annotated parameters (`@Named`, `@Qualifier`, etc.) always injected
- [x] User log traces when parameters are skipped

```kotlin
class Service(val a: A, val name: String = "default")
single<Service>()
// With skipDefaultValues=true:  Service(scope.get())  -- name uses default
// With skipDefaultValues=false: Service(scope.get(), scope.get())
```

### 2.3 Compile-Time Dependency Validation ✅
Detect missing dependencies at compile time instead of runtime crashes.
- [x] Per-module validation (A1): local definitions + explicit includes
- [x] Validate non-nullable parameters have definitions
- [x] Validate `Lazy<T>` inner type is provided
- [x] Validate `@Named`/`@Qualifier` qualifiers match (with hints for mismatches)
- [x] Validate scoped dependencies are in correct scope
- [x] Skip safe parameters: nullable, `@InjectedParam`, `@Property`, `List<T>`, defaults
- [x] Clear error messages with module/parameter context
- [x] Configuration group validation (A2): `@Configuration` sibling modules share definitions
- [x] startKoin full-graph validation (A3): validates all modules assembled by `startKoin<T>()`
- [x] Cross-Gradle-module validation (C): definitions from dependency JARs via hint functions
- [x] `@Provided` annotation: marks types as externally available, skips safety validation
- [x] Android framework whitelist: `Context`, `Activity`, `Application`, `Fragment`, `SavedStateHandle`, `WorkerParameters`
- [x] Cross-module function hint metadata (C2): encode qualifier, scope, bindings in hint parameters
  - [x] Qualifier propagation: `@Named`/`@Qualifier` via `qualifier_<name>` or `qualifierType` hint params
  - [x] Scope propagation: `@Scope(MyScope::class)` via `scope` hint parameter
  - [x] Bindings propagation: return type supertypes via `binding0`, `binding1`, ... hint params
- [x] DSL validation (B): validate `single<T>()`, `factory<T>()` in hand-written modules
- [x] Call-site validation (A4): validates `get<T>()`, `inject<T>()`, `koinViewModel<T>()` call sites with deferred cross-module hints
- [x] Cross-module custom qualifier discovery via qualifier hint functions
- See [COMPILE_TIME_SAFETY.md](COMPILE_TIME_SAFETY.md) for detailed design and implementation

**Done:**
- [x] Package filtering: scan now matches both function's package and return type's package

**Remaining for 1.0:**
- [ ] Property validation (D): `@Property`/`@PropertyValue` matching — see §2.7
- [ ] `@ScopeId` support — see §2.8
- [ ] `Scope` parameter injection — see §2.9
- [ ] DSL `@Configuration` bridge for full safety — see §2.10

### 2.4 `@Monitor` Annotation Support ✅
Function interception for logging and performance capture:
- [x] `@Monitor` annotation on class or function
- [x] Generate wrapper code to intercept function calls
- [x] Log function entry/exit with parameters
- [x] Capture execution time and performance metrics
- [x] Integration with Koin's logging/monitoring API

```kotlin
@Monitor
class MyService {
    fun fetchData(): Data { ... }  // Calls will be intercepted
}

// Or on specific functions
class MyService {
    @Monitor
    fun fetchData(): Data { ... }  // Only this function intercepted
}
```

### 2.5 JSR-330 Compatibility Toggle
Allow enabling/disabling JSR-330 annotation support via Gradle property:
- [ ] Add `jsr330` option to `koinCompiler { }` DSL (default: `true`)
- [ ] Pass option to compiler plugin via `CommandLineProcessor`
- [ ] Skip `jakarta.inject.*` and `javax.inject.*` annotation processing when disabled
- [ ] Document in README

```kotlin
// build.gradle.kts
koinCompiler {
    jsr330 = false  // Disable jakarta.inject/javax.inject support
}
```

### 2.6 Prebuilt Module Index
Generate pre-populated HashMap for instant startup — see [PREBUILT_INDEX.md](PREBUILT_INDEX.md).
- [ ] Design finalized (static KoinIndex, buildEntry, indexedModule)
- [ ] koin-core API additions (KoinIndex, buildEntry, indexedModule, loadIndex)
- [ ] Compiler: generate `buildKoinIndex { buildEntry(...) }` instead of `module { buildSingle(...) }`
- [ ] Compiler: transform `startKoin<T>()` to use `loadIndex()` / map assignment
- [ ] Unsafe DSL detection (hand-written lambdas → compiler error when `prebuiltIndex = true`)
- [ ] `prebuiltIndex` Gradle option (default: `false` in 1.x, `true` in 2.x)

### 2.7 Property Validation (D): `@Property`/`@PropertyValue` Matching
Validate that `@Property("key")` parameters have a corresponding `@PropertyValue("key")` default.
- [ ] Collect all `@PropertyValue("key")` registrations (already in `PropertyValueRegistry`)
- [ ] In `BindingRegistry` / `ParameterAnalyzer`: for `@Property("key")` params, check key exists in registry
- [ ] Cross-module: generate hint for `@PropertyValue` keys so downstream modules can validate
- [ ] Error message: `Missing property default: "api.timeout" — no @PropertyValue("api.timeout") found`
- [ ] Skip validation if property has a default value in `@PropertyValue`

```kotlin
// Should compile-error if no @PropertyValue("api.timeout") exists anywhere:
@Factory
class ApiClient(@Property("api.timeout") val timeout: Int)
```

### 2.8 `@ScopeId` Parameter Support
Support `@ScopeId` annotation for injecting values from a named Koin scope.
Currently **not recognized** by the compiler plugin — treated as a regular dependency, generating wrong code.

- [ ] Add `SCOPE_ID` to `KoinAnnotationFqNames.kt`
- [ ] `ParameterAnalyzer`: detect `@ScopeId` → classify as `isScopeId = true`, extract scope name
- [ ] `KoinArgumentGenerator`: generate `getKoin().getScope("scopeId").get<T>()` for `@ScopeId` params
- [ ] `BindingRegistry`: skip validation for `@ScopeId` params (runtime scope, can't validate statically)
- [ ] Add tests: box test + diagnostic test

```kotlin
// Today: generates scope.get<UserSession>() — WRONG
// Target: generates getKoin().getScope("user_session").get<UserSession>()
@Factory
class ProfileService(@ScopeId("user_session") val session: UserSession)
```

### 2.9 `Scope` Parameter Injection
In previous Koin Annotations (KSP), constructor parameters of type `org.koin.core.scope.Scope`
were injected with the current scope instance. Currently **not supported** — generates `scope.get<Scope>()`
which fails (tries to look up Scope as a dependency).

**Decision: require `@Provided` on Scope parameters.** This skips safety validation (correct, since
injecting Scope enables dynamic `scope.get<Anything>()` which can't be statically validated).

- [ ] `KoinArgumentGenerator`: detect param type `org.koin.core.scope.Scope` → generate `scope` (the extension receiver itself)
- [ ] `ParameterAnalyzer`: detect Scope type → skip validation (or require `@Provided`)
- [ ] Document: `@Provided` on `Scope` params is required, warn that it bypasses compile safety
- [ ] Consider: emit a warning when `Scope` is injected (encourages explicit deps instead)
- [ ] Add tests

```kotlin
// User must annotate with @Provided to acknowledge safety bypass:
@Scoped
class ScopedService(@Provided val scope: Scope) {
    fun dynamicLookup() = scope.get<SomeDep>()  // Not validated at compile time
}
```

### 2.10 DSL `@Configuration` Bridge for Compile Safety
Allow `@Configuration` on module property declarations so DSL-style modules can participate
in full-graph A3 validation at `startKoin<T>()`. Currently, plain `startKoin { modules(...) }`
can't be fully validated — the compiler doesn't know which modules are loaded.

**Approach:**
- `@Configuration("label")` on `val myModule = module { }` properties
- FIR discovers annotated properties via `predicateBasedProvider.getSymbolsByPredicate(configurationPredicate)`
  (already matches any annotated declaration — currently filtered to classes only, needs `FirPropertySymbol` handling)
- FIR generates `configuration_<label>` hint for the property (same mechanism as class modules)
- IR Phase 2: when collecting `DslDef`s inside `module { }` lambda, check if enclosing property
  has `@Configuration` → tag definitions with the label
- A3 at `startKoin<T>()`: resolve configuration labels → all tagged DSL definitions join the graph
- No explicit `includes` needed between modules in the same configuration group — A3 sees everything

**Changes needed:**
- [ ] Add `AnnotationTarget.PROPERTY` to `@Configuration`'s `@Target` (in koin-annotations)
- [ ] FIR: in `configurationModules` lazy, also filter for `FirPropertySymbol` (not just `FirClassSymbol`)
- [ ] FIR: generate `configuration_<label>` hints for annotated properties
- [ ] IR Phase 2 (`KoinDSLTransformer`): detect `@Configuration` on parent property of `module { }` call
- [ ] IR Phase 2: associate collected `DslDef`s with configuration label
- [ ] `@KoinApplication`: add `configurations` attribute to reference labels (e.g., `configurations = ["app"]`)
- [ ] A3 (`CompileSafetyValidator.validateFullGraph`): resolve configuration labels → include DSL definitions
- [ ] Add tests: DSL module with `@Configuration` validated at A3

```kotlin
@Configuration("app")
val networkModule = module {
    single<HttpClient>()
    single<ApiService>()  // depends on HttpClient — OK at A3 level
}

@Configuration("app")
val dataModule = module {
    single<Repository>()  // depends on ApiService — OK at A3 level
}

// Annotation module in same group:
@Module
@Configuration("app")
class AppModule {
    @Single fun analytics(): Analytics = AnalyticsImpl()
}

// Entry point — compiler resolves "app" → all 3 modules → full A3 validation
@KoinApplication(configurations = ["app"])
object MyApp

startKoin<MyApp> { }
```

**Note:** Per-module A2 validation is limited for DSL modules (no `includes` mechanism on properties).
The real safety net is A3 at `startKoin<T>()` where all modules in the configuration are visible together.

---

## Multi-Kotlin Version Compatibility (Deferred)

**Current approach:** Follow latest Kotlin versions and adapt as needed. No complex versioning scheme required.

**Background:** Kotlin compiler plugins are not binary compatible across minor versions. A plugin compiled with Kotlin 2.2.21 won't work with Kotlin 2.3.x. If needed in the future, version-aligned releases (`0.1.0-kotlin-2.2.21`) can be implemented.