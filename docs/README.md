# Koin Compiler Plugin - Documentation

Central documentation hub for debugging, developing, and understanding the Koin Compiler Plugin.

## Quick Links

| Document | Purpose |
|----------|---------|
| [DEBUGGING.md](DEBUGGING.md) | **Start here for debugging** - Logging, common issues, step-by-step debugging |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Project structure, compilation flow, key files |
| [TRANSFORMATIONS.md](TRANSFORMATIONS.md) | All transformation examples with before/after code |
| [COMPILER_BASICS.md](COMPILER_BASICS.md) | Kotlin compiler plugin fundamentals (IR, FIR, visitors) |
| [FIR_PROCESSING.md](FIR_PROCESSING.md) | **FIR deep dive** - Source types, KMP phases, synthetic files, cross-module discovery |
| [PLUGIN_HINTS.md](PLUGIN_HINTS.md) | Cross-module discovery via hint functions |

## Project Overview

```
koin-compiler-plugin/
├── koin-compiler-plugin/           # FIR + IR compiler plugin
├── koin-compiler-gradle-plugin/    # Gradle integration
└── test-apps/                      # Test samples
```

> **Note:** The plugin support library (stub + target functions) is part of the main Koin repository (`koin-annotations`).

## Quick Commands

```bash
# Build and publish plugin locally
./install.sh

# Run sample tests
cd test-apps && ./gradlew :sample-app:jvmTest

# View plugin logs during compilation (both FIR and IR)
cd test-apps && ./gradlew :sample-app:compileKotlinJvm 2>&1 | grep "\[Koin-Plugin"

# Enable debug logging in build.gradle.kts: koinCompiler { debugLogs = true }
```

## Key Files

### Compiler Plugin

| File | Phase | Purpose |
|------|-------|---------|
| `fir/KoinModuleFirGenerator.kt` | FIR | Generates `.module` property + hints |
| `ir/KoinAnnotationProcessor.kt` | IR-1 | Processes @Singleton/@Factory (classes, module functions, top-level functions) |
| `ir/KoinDSLTransformer.kt` | IR-2 | Transforms `single<T>()` |
| `ir/KoinStartTransformer.kt` | IR-3 | Transforms `startKoin<T>()` |
| `KoinConfigurationRegistry.kt` | FIR→IR | Cross-phase communication |

### Plugin Support (Stubs + Targets) — Included in Koin

| File | Purpose |
|------|---------|
| `BaseDSLExt.kt` | Stub functions: `single<T>()`, `factory<T>()` |
| `ModuleExt.kt` | Target functions: `buildSingle()`, `buildFactory()` |
| `ViewModelDSLExt.kt` | Stub functions: `viewModel<T>()` |
| `WorkerDSLExt.kt` | Stub functions: `worker<T>()` |
| `CreateDSL.kt` | Stub functions: `Scope.create(::T)` (constructor reference) |

## Compilation Flow

```
FIR Phase (declarations)     IR Phase (bodies/transformations)
         │                              │
         ▼                              ▼
┌────────────────────┐    ┌──────────────────────────────────┐
│ KoinModuleFirGen   │    │ IR-0: KoinHintTransformer      │
│ - @Module scan     │    │ IR-1: KoinAnnotationProcessor    │
│ - .module property │ →  │ IR-2: KoinDSLTransformer  │
│ - hint functions   │    │ IR-3: KoinStartTransformer   │
└────────────────────┘    └──────────────────────────────────┘
```

## Common Debugging Scenarios

### "single<T>() not transforming"
1. Check IR logs: `grep "Intercepting single" compilation.log`
2. Verify receiver type is from `org.koin.core` or `org.koin.dsl`
3. See [DEBUGGING.md#transformation-not-happening](DEBUGGING.md#73-transformation-not-happening)

### ".module property not found"
1. Verify class has BOTH `@Module` AND `@ComponentScan`
2. Enable debug logs: `koinCompiler { debugLogs = true }` and check compilation output
3. See [DEBUGGING.md#module-property-not-generated](DEBUGGING.md#72-module-property-not-generated)

### "No modules to inject"
1. Check `@KoinApplication(modules = [...])` annotation
2. Cross-module discovery is limited - use explicit modules
3. See [DEBUGGING.md#no-modules-to-inject](DEBUGGING.md#71-no-modules-to-inject)

## Related Files

- [../README.md](../README.md) - Project README
