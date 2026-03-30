# Koin Compiler Plugin

A native Kotlin Compiler Plugin for Koin dependency injection. Transforms `single<T>()` DSL calls and processes `@Singleton`/`@Factory` annotations at compile-time.

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
| `ir/KoinStartTransformer.kt` | Transforms `startKoin<T>()`, `koinApplication<T>()`, `koinConfiguration<T>()`, `withConfiguration<T>()` |
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
| `@Provided` | Marks type/parameter as externally available (skips safety validation) |
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

## Release

```bash
# Release to Maven Central
./release-to-central.sh

# Release to Gradle Plugin Portal
./release-to-gradle-portal.sh
```

## Plugin Configuration

```kotlin
// build.gradle.kts
koinCompiler {
    userLogs = true           // Component detection logs
    debugLogs = true          // Internal processing logs (verbose)
    unsafeDslChecks = true    // Validates create() is the only instruction in lambda (default: true)
    skipDefaultValues = true  // Skip injection for parameters with default values (default: true)
    compileSafety = true       // Compile-time dependency validation (default: true)
}
```

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

## Compatibility

- **Koin**: 4.2.0-RC2+
- **Kotlin**: K2 compiler required (2.3.x+)
