# Cross-Module Discovery via Hint Functions

This document describes Koin's hint-based discovery mechanism for cross-module `@Configuration` scanning.

## Overview

Kotlin compiler plugins have a fundamental limitation: `IrGenerationExtension` can only modify the current compilation unit and cannot iterate/scan classes from dependencies (JARs). Koin solves this using **synthetic marker functions** (hints) that encode module metadata, allowing downstream modules to query them via `FirSession.symbolProvider`.

## How It Works

### Two-Phase Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Module A (Library)                                             │
│  ┌─────────────────┐    ┌─────────────────────────────────────┐ │
│  │ @Configuration  │───▶│ FIR: Create hint function symbol    │ │
│  │ class MyModule  │    │ IR: Generate hint + register        │ │
│  └─────────────────┘    │     as metadata-visible             │ │
│                         └─────────────────────────────────────┘ │
│                                        │                        │
│                                        ▼                        │
│                         ┌─────────────────────────────────────┐ │
│                         │ org.koin.plugin.hints package:      │ │
│                         │ fun configuration_default(          │ │
│                         │     contributed: MyModule): Unit    │ │
│                         └─────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                                         │
                              (compiled JAR)
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  Module B (App)                                                 │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ FIR phase:                                                  ││
│  │ session.symbolProvider.getTopLevelFunctionSymbols(          ││
│  │     "org.koin.plugin.hints", "configuration_default"        ││
│  │ )                                                           ││
│  │ → Returns MyModule from hint function parameter             ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

## Hint Function Structure

### Package & Naming Convention

- **Package:** `org.koin.plugin.hints` (constant across all modules)
- **Function name:** `configuration_<label>` (e.g., `configuration_default`, `configuration_test`)
- **Parameter name:** Always `contributed`
- **Parameter type:** The contributing module class
- **Return type:** Always `Unit`
- **Body:** Always `error("Stub!")`

### Generated Example

```kotlin
// Source (Module A)
@Module
@ComponentScan
@Configuration("default", "prod")
class MyModule

// Generated hints (org.koin.plugin.hints package)
package org.koin.plugin.hints

fun configuration_default(contributed: MyModule): Unit = error("Stub!")
fun configuration_prod(contributed: MyModule): Unit = error("Stub!")
```

## Implementation Details

### FIR Phase: Symbol Generation

In `KoinModuleFirGenerator.kt`:

1. **Predicate registration**: Register predicate to find `@Module @ComponentScan @Configuration` classes
2. **Hint function creation**: For each configuration label, create a hint function symbol
3. **Unique naming**: Function names include the label to support label-based filtering

```kotlin
// For each @Configuration module, generate hint functions per label
override fun getTopLevelCallableIds(): Set<CallableId> {
    return configurationModules.flatMap { module ->
        module.labels.map { label ->
            CallableId(HINTS_PACKAGE, Name.identifier("configuration_$label"))
        }
    }.toSet()
}
```

### IR Phase: Body Generation & Metadata Registration

In `KoinHintTransformer.kt`:

1. **Body generation**: Fill hint function body with `error("Stub!")`
2. **Metadata registration**: Register via `metadataDeclarationRegistrar` for downstream visibility

```kotlin
override fun visitSimpleFunction(declaration: IrSimpleFunction): IrSimpleFunction {
    if (isHintFunction && declaration.body == null) {
        declaration.body = generateHintFunctionBody(declaration)
        context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(declaration)
    }
    return declaration
}
```

### Discovery Phase: Querying Hints

In `KoinModuleFirGenerator.kt` (downstream module):

```kotlin
// Query all hint functions for requested labels
fun discoverConfigurationModules(labels: List<String>): List<ClassId> {
    return labels.flatMap { label ->
        session.symbolProvider.getTopLevelFunctionSymbols(
            HINTS_PACKAGE,
            Name.identifier("configuration_$label")
        ).mapNotNull { functionSymbol ->
            functionSymbol.valueParameterSymbols.firstOrNull()
                ?.resolvedReturnType
                ?.classId
        }
    }.distinct()
}
```

## Configuration Labels

Labels allow filtering which `@Configuration` modules are discovered:

```kotlin
// Module with default label
@Configuration
class ProdModule

// Module with test label
@Configuration("test")
class TestModule

// Module with multiple labels
@Configuration("test", "prod")
class SharedModule

// App requesting specific labels
@KoinApplication(configurations = ["test"])
object TestApp  // Discovers TestModule and SharedModule
```

## Key Files

| File | Purpose |
|------|---------|
| `KoinModuleFirGenerator.kt` | FIR: Generates hint function symbols, discovers from JARs |
| `KoinHintTransformer.kt` | IR: Fills bodies + registers as metadata-visible |
| `KoinConfigurationRegistry.kt` | FIR→IR communication |
| `KoinStartTransformer.kt` | IR: Queries hints and injects modules into `startKoin<T>()` |

## Why Hint Functions?

1. **Cross-compilation unit visibility**: Functions in metadata can be queried by downstream modules
2. **FIR-level discovery**: Can discover during FIR phase before IR transformation
3. **Type-safe encoding**: Module class encoded as parameter type
4. **Label support**: Function naming convention enables label-based filtering
5. **No runtime overhead**: Functions throw if called (never invoked at runtime)
