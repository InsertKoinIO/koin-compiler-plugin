# Cross-Module Discovery via Hint Functions

This document describes Koin's hint-based discovery mechanism for cross-module communication. Hint functions are synthetic marker functions that encode metadata about definitions, modules, qualifiers, and call sites, allowing downstream modules to discover them at compile time.

## Overview

Kotlin compiler plugins have a fundamental limitation: `IrGenerationExtension` can only modify the current compilation unit and cannot iterate/scan classes from dependencies (JARs). Koin solves this using **synthetic marker functions** (hints) that encode module metadata, allowing downstream modules to query them via `FirSession.symbolProvider` or IR `referenceFunctions`.

All hint functions share these properties:
- **Package:** `org.koin.plugin.hints` (constant: `KoinPluginConstants.HINTS_PACKAGE`)
- **Parameter name:** `contributed`
- **Return type:** `Unit`
- **Body:** `error("Stub!")` (never invoked at runtime)

## Hint Type Summary

| Hint Type | Prefix / Name | Generated In | Body Filled In | Discovered By |
|-----------|--------------|--------------|----------------|---------------|
| Configuration | `configuration_<label>` | FIR (KoinModuleFirGenerator) | IR Phase 0 (KoinHintTransformer) | FIR (symbolProvider), IR (referenceFunctions) |
| Definition | `definition_<type>` | FIR (KoinModuleFirGenerator) | IR Phase 0 (KoinHintTransformer) | IR Phase 3 (KoinStartTransformer) |
| Function Definition | `definition_function_<type>` | FIR (KoinModuleFirGenerator) | IR Phase 0 (KoinHintTransformer) | IR Phase 3 (KoinStartTransformer) |
| Module Definition | `moduledef_<module>_<func>` | FIR (KoinModuleFirGenerator) | IR Phase 0 (KoinHintTransformer) | IR Phase 3 (KoinStartTransformer) |
| Component Scan | `componentscan_<module>_<type>` | IR Phase 1b (KoinAnnotationProcessor) | Generated inline (not KoinHintTransformer) | IR Phase 3 (KoinStartTransformer) |
| Component Scan Function | `componentscanfunc_<module>_<type>` | IR Phase 1b (KoinAnnotationProcessor) | Generated inline (not KoinHintTransformer) | IR Phase 3 (KoinStartTransformer) |
| DSL Definition | `dsl_<type>` | IR Phase 2.5 (KoinIrExtension) | Generated inline | IR Phase 3.1, 3.5, 3.6 |
| Qualifier | `qualifier` | FIR (KoinModuleFirGenerator) | IR Phase 0 (KoinHintTransformer) | IR (QualifierExtractor.discoverQualifierHints) |
| Call-site | `callsite` | IR Phase 3.5 (KoinIrExtension) | Generated inline | IR Phase 3.6 (validateCallSiteHintsFromDependencies) |

## Two-Phase Architecture

Most hint functions follow a two-phase pattern: the FIR phase creates the function symbol (declaration without a body), and the IR phase fills the body and registers it as metadata-visible for downstream compilation units.

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

## Hint Types

### 1. Configuration Hints

**Prefix:** `configuration_<label>` (constant: `HINT_FUNCTION_PREFIX`)

Configuration hints enable cross-module discovery of `@Configuration`-annotated modules. When a module is annotated with `@Configuration("label1", "label2")`, one hint function is generated per label.

**Parameter type:** The contributing `@Configuration` module class.

**Generated example:**

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

**Generation (FIR):** In `KoinModuleFirGenerator.kt`, predicates find `@Module @ComponentScan @Configuration` classes and generate hint function symbols per label:

```kotlin
override fun getTopLevelCallableIds(): Set<CallableId> {
    return configurationModules.flatMap { module ->
        module.labels.map { label ->
            CallableId(HINTS_PACKAGE, Name.identifier("configuration_$label"))
        }
    }.toSet()
}
```

**Body generation (IR):** In `KoinHintTransformer.kt` (Phase 0), the body is filled and the function is registered as metadata-visible:

```kotlin
override fun visitSimpleFunction(declaration: IrSimpleFunction): IrSimpleFunction {
    if (isHintFunction && declaration.body == null) {
        declaration.body = generateHintFunctionBody(declaration)
        context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(declaration)
    }
    return declaration
}
```

**Discovery:** In downstream modules, `KoinModuleFirGenerator.kt` queries hints via the symbol provider:

```kotlin
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

**Configuration labels** allow filtering which modules are discovered:

```kotlin
@Configuration
class ProdModule                       // label: "default"

@Configuration("test")
class TestModule                       // label: "test"

@Configuration("test", "prod")
class SharedModule                     // labels: "test", "prod"

@KoinApplication(configurations = ["test"])
object TestApp  // Discovers TestModule and SharedModule
```

### 2. Definition Hints

**Prefix:** `definition_<type>` (constant: `DEFINITION_HINT_PREFIX`)

Definition hints encode annotated classes (`@Singleton`, `@Factory`, etc.) so that downstream modules can discover them for compile-time safety validation.

**Parameter type:** The annotated class being defined.

**Generated example:**

```kotlin
// Source (Module A)
@Singleton
class UserRepository(val db: Database)

// Generated hint
package org.koin.plugin.hints

fun definition_single(contributed: UserRepository): Unit = error("Stub!")
```

**Lifecycle:**
- Generated in FIR by `KoinModuleFirGenerator`
- Body filled in IR Phase 0 by `KoinHintTransformer`
- Discovered in IR Phase 3 by `KoinStartTransformer` for cross-module safety validation

### 3. Function Definition Hints

**Prefix:** `definition_function_<type>` (constant: `DEFINITION_FUNCTION_HINT_PREFIX`)

Function definition hints encode annotated functions that are declared inside `@Module` classes. The parameter type encodes the function's return type (the type being provided to the DI container).

**Generated example:**

```kotlin
// Source (Module A)
@Module
class DataModule {
    @Singleton
    fun provideDatabase(): DatabaseService = PostgresDatabase()
}

// Generated hint
package org.koin.plugin.hints

fun definition_function_single(contributed: DatabaseService): Unit = error("Stub!")
```

**Lifecycle:**
- Generated in FIR by `KoinModuleFirGenerator`
- Body filled in IR Phase 0 by `KoinHintTransformer`
- Discovered in IR Phase 3 by `KoinStartTransformer`

### 4. Module Definition Hints

**Prefix:** `moduledef_<module>_<func>` (constant: `MODULE_DEFINITION_HINT_PREFIX`)

Module definition hints track individual function definitions within a `@Module` class. The function name encodes both the module identity and the providing function name.

**Generated example:**

```kotlin
// Source (Module A)
@Module
class DaosModule {
    @Singleton
    fun providesTopicDao(): TopicDao = TopicDaoImpl()
}

// Generated hint
package org.koin.plugin.hints

fun moduledef_comExampleDaosModule_providesTopicDao(contributed: TopicDao): Unit = error("Stub!")
```

**Lifecycle:**
- Generated in FIR by `KoinModuleFirGenerator`
- Body filled in IR Phase 0 by `KoinHintTransformer`
- Discovered in IR Phase 3 by `KoinStartTransformer`

### 5. Component Scan Hints

**Prefix:** `componentscan_<module>_<type>` (constant: `COMPONENT_SCAN_HINT_PREFIX`)

Component scan hints are generated when a `@Module` with `@ComponentScan` discovers annotated classes within its scanned package. These encode the results of the scan so downstream modules know which types are provided.

**Generated example:**

```kotlin
// Source: @ComponentScan("com.example") finds @Singleton class UserRepo
package org.koin.plugin.hints

fun componentscan_comExampleCoreModule_single(contributed: UserRepo): Unit = error("Stub!")
```

**Lifecycle:**
- Generated in IR Phase 1b by `KoinAnnotationProcessor.generateModuleScanHints`
- Bodies are generated inline (not via `KoinHintTransformer`)
- Discovered in IR Phase 3 by `KoinStartTransformer`

### 6. Component Scan Function Hints

**Prefix:** `componentscanfunc_<module>_<type>` (constant: `COMPONENT_SCAN_FUNCTION_HINT_PREFIX`)

Similar to component scan hints, but for top-level annotated functions discovered during component scanning.

**Generated example:**

```kotlin
// Source: @ComponentScan("com.example") finds @Singleton fun provideCache(): CacheService
package org.koin.plugin.hints

fun componentscanfunc_comExampleCoreModule_single(contributed: CacheService): Unit = error("Stub!")
```

**Lifecycle:**
- Generated in IR Phase 1b by `KoinAnnotationProcessor`
- Bodies are generated inline (not via `KoinHintTransformer`)
- Discovered in IR Phase 3 by `KoinStartTransformer`

### 7. DSL Definition Hints

**Prefix:** `dsl_<type>` (constant: `DSL_DEFINITION_HINT_PREFIX`)

DSL definition hints encode types registered via the DSL (`single<T>()`, `factory<T>()`, etc.) for cross-module compile-time safety validation. Unlike annotation-based hints, these are generated entirely in the IR phase since DSL calls are only visible after IR transformation.

The first parameter is the declared type; the others carry the rest of the definition by name, so a consumer reads an allow-list of prefixes and ignores what it does not know:

| Parameter | Meaning |
|---|---|
| `binding<i>` | an explicit `bind<T>()` |
| `reqsEncoded`, `req<i>_<name>`, `req<i>f_<flag>`, `req<i>q_<qualifier>`, `req<i>qt` | the definition's own requirements, with flags and qualifiers |
| `module_<id>` | the `module { }` val or function that declares it (`.` → `$`) |
| `providerOnly` | a `create(::function)` definition: provides its type, requirements unknown |
| `root` | a `factory`/`viewModel` declared outside any `ScopeDSL` block, so a root provider (1.2.3) |
| `qualifier_<name>`, `qualifierType` | string / type qualifier |
| `erased<i>_<ClassId>` | a type this module cannot name, erased to `Any` |
| `dup<n>_<position>` | signature-uniqueness marker, no content (see `HintFiles.kt`) |

**Generated example:**

```kotlin
// Source (Module A)
val myModule = module {
    single<UserRepository> { UserRepositoryImpl(get()) }
}

// Generated hint
package org.koin.plugin.hints

fun dsl_single(contributed: UserRepository, reqsEncoded: Unit, module_pkg$myModule: Unit, providerOnly: Unit): Unit = error("Stub!")
```

**Lifecycle:**
- Generated in IR Phase 2.5 by `generateDslDefinitionHints` in `KoinIrExtension.kt`
- Discovered in IR Phase 3.1 by `discoverDslDefinitionsFromHints`
- Also used in Phase 3.5 and 3.6 for cross-module validation

### 8. Qualifier Hints

**Name:** `qualifier` (constant: `QUALIFIER_HINT_NAME`)

Qualifier hints enable cross-module discovery of custom `@Qualifier` annotation classes. When an annotation class is meta-annotated with `@Qualifier`, a hint function is generated so downstream modules can recognize it as a qualifier.

**Parameter type:** The custom qualifier annotation class.

**Generated example:**

```kotlin
// Source (Module A)
@Qualifier
annotation class DatabaseQualifier

// Generated hint
package org.koin.plugin.hints

fun qualifier(contributed: DatabaseQualifier): Unit = error("Stub!")
```

**Lifecycle:**
- Generated in FIR by `KoinModuleFirGenerator` when it detects an annotation class with `@Qualifier` meta-annotation
- Body filled in IR Phase 0 by `KoinHintTransformer`
- Discovered by `QualifierExtractor.discoverQualifierHints` for cross-module custom qualifier detection

### 9. Call-site Hints

**Name:** `callsite` (constant: `CALLSITE_HINT_NAME`)

Call-site hints enable deferred dependency validation across module boundaries. When a call site (e.g., `get<T>()` or `inject<T>()`) cannot be resolved locally in a library module, a hint is generated so the app module can validate it has the required type available.

**Parameter type:** The required type that needs to be provided.

**Generated example:**

```kotlin
// Source (Library module): get<AuthService>() used but AuthService not defined locally
package org.koin.plugin.hints

fun callsite(contributed: AuthService): Unit = error("Stub!")
```

**Lifecycle:**
- Generated in IR Phase 3.5 by `generateCallSiteHints` in `KoinIrExtension.kt`
- Discovered in IR Phase 3.6 by `validateCallSiteHintsFromDependencies` in the app module
- If the app module cannot resolve the required type, a compile-time error is reported

## Key Files

| File | Purpose |
|------|---------|
| `KoinPluginConstants.kt` | All hint prefixes and names as constants |
| `KoinModuleFirGenerator.kt` | FIR: Generates hint function symbols for configuration, definition, function definition, module definition, and qualifier hints |
| `KoinHintTransformer.kt` | IR Phase 0: Fills bodies + registers as metadata-visible for FIR-generated hints |
| `KoinAnnotationProcessor.kt` | IR Phase 1b: Generates component scan and component scan function hints |
| `KoinIrExtension.kt` | IR Phase 2.5 / 3.5: Generates DSL definition hints and call-site hints |
| `KoinStartTransformer.kt` | IR Phase 3: Discovers hints from dependencies for safety validation |
| `QualifierExtractor.kt` | Discovers qualifier hints for cross-module custom qualifier detection |

## Why Hint Functions?

1. **Cross-compilation unit visibility**: Functions in metadata can be queried by downstream modules
2. **FIR-level discovery**: Can discover during FIR phase before IR transformation
3. **Type-safe encoding**: Types are encoded as parameter types, preserving full type information
4. **Label support**: Function naming convention enables label-based filtering (configuration hints)
5. **No runtime overhead**: Functions throw if called (never invoked at runtime)
6. **Phased generation**: FIR-generated hints enable early discovery; IR-generated hints capture information only available after transformation
