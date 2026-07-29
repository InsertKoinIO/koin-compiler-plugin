# Koin Compiler Plugin - Architecture

## Project Structure

```
koin-compiler-plugin/
├── koin-compiler-plugin/                    # The compiler plugin (FIR + IR)
│   ├── src/org/koin/compiler/plugin/
│   │   ├── fir/                        # FIR phase (declaration generation)
│   │   │   ├── KoinModuleFirGenerator.kt
│   │   │   └── KoinPluginRegistrar.kt
│   │   ├── ir/                         # IR phase (code transformation)
│   │   │   ├── KoinIrExtension.kt
│   │   │   ├── KoinAnnotationProcessor.kt
│   │   │   ├── KoinDSLTransformer.kt
│   │   │   ├── KoinStartTransformer.kt
│   │   │   └── KoinHintTransformer.kt
│   │   ├── KoinConfigurationRegistry.kt
│   │   ├── KoinCommandLineProcessor.kt
│   │   └── KoinPluginComponentRegistrar.kt
│   ├── testData/                       # Test input files
│   ├── test-fixtures/                  # Test framework
│   └── test-gen/                       # Generated test classes
│
├── koin-compiler-gradle-plugin/        # Gradle plugin for easy integration
│
└── test-apps/                          # Test samples (separate Gradle project)
    ├── sample-app/                     # KMP sample application
    └── sample-feature-module/          # Multi-module test
```

## Plugin Registration Chain

```
META-INF/services/
├── org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
│   └── KoinPluginComponentRegistrar.kt
│       └── Registers: KoinPluginRegistrar (FIR) + KoinIrExtension (IR)
│
└── org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
    └── KoinCommandLineProcessor.kt
        └── Plugin ID: "io.insert-koin.compiler.plugin"
```

## Compilation Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         KOTLIN COMPILATION PHASES                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ PHASE 1: SOURCE PARSING                                              │   │
│  │   .kt files → Abstract Syntax Tree (AST)                             │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                              ↓                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ PHASE 2: FIR (Frontend IR)                                           │   │
│  │   File: KoinModuleFirGenerator.kt                                    │   │
│  │                                                                       │   │
│  │   What happens:                                                       │   │
│  │   1. Scans for @Module @ComponentScan classes via predicate          │   │
│  │   2. GENERATES declarations (no bodies yet):                         │   │
│  │      - val MyModule.module: Module (extension property)              │   │
│  │      - fun configuration_<label>(contributed: T) hints               │   │
│  │      - fun definition_<type>(contributed: T) for annotated classes   │   │
│  │      - fun definition_function_<type>(contributed: T) for @Module fns│   │
│  │      - fun moduledef_<module>_<func>(contributed: T) per-function    │   │
│  │      - fun qualifier(contributed: T) for custom @Qualifier classes   │   │
│  │   3. Discovers @Configuration modules from JARs via hint functions   │   │
│  │   4. Populates KoinConfigurationRegistry with module names           │   │
│  │                                                                       │   │
│  │   Key methods:                                                        │   │
│  │   - registerPredicates() → Registers @Module lookup                  │   │
│  │   - getTopLevelCallableIds() → Returns what to generate              │   │
│  │   - generateProperties() → Creates .module extension                 │   │
│  │   - generateFunctions() → Creates hint functions                     │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                              ↓                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ PHASE 3: IR (Intermediate Representation)                            │   │
│  │   File: KoinIrExtension.kt                                 │   │
│  │                                                                       │   │
│  │   Phase 0: KoinHintTransformer (full tree walk)                      │   │
│  │     └── Fills bodies for FIR-generated hint functions                │   │
│  │         - configuration(contributed: Module) → error("Stub!")        │   │
│  │         - definition, moduledef, qualifier hints                     │   │
│  │         - Registers hints as metadata-visible for cross-module       │   │
│  │                                                                       │   │
│  │   Phase 1: KoinAnnotationProcessor (full tree walk)                  │   │
│  │     └── Scans @Singleton/@Factory/@KoinViewModel on:                 │   │
│  │         - Classes                                                     │   │
│  │         - Functions inside @Module classes                            │   │
│  │         - Top-level functions (discovered by @ComponentScan)          │   │
│  │     └── Collects definitions only — does NOT validate here (1.1.0):  │   │
│  │         per-module validation (A1/A2) was removed; the full graph    │   │
│  │         is validated once, at the entry point (Phase 3, A3)          │   │
│  │     └── FILLS BODY of FIR-generated .module property:                │   │
│  │         val MyModule.module = module {                               │   │
│  │             buildSingle(A::class, null) { A(get()) }                 │   │
│  │             buildFactory(B::class, null) { B(get(), get()) }         │   │
│  │             buildSingle(C::class, null) { provideC(get()) }  // fn   │   │
│  │         }                                                             │   │
│  │                                                                       │   │
│  │   Phase 2: KoinDSLTransformer (full tree walk)                       │   │
│  │     └── Transforms DSL calls:                                        │   │
│  │         single<T>() → buildSingle(T::class, null) { T(get()) }       │   │
│  │         scope.create(::T) → T(scope.get(), scope.get())              │   │
│  │     └── Collects DslDef definitions for safety graph                 │   │
│  │     └── Collects PendingCallSiteValidation for get<T>(), inject<T>() │   │
│  │                                                                       │   │
│  │   Phase 2.5: generateDslDefinitionHints() (no tree walk)             │   │
│  │     └── Generates dsl_single, dsl_factory hint functions             │   │
│  │         for cross-module DSL discovery (iterates collected list)      │   │
│  │                                                                       │   │
│  │   Phase 3: KoinStartTransformer (full tree walk)                     │   │
│  │     └── Transforms app entry points:                                 │   │
│  │         startKoin<MyApp>() → startKoinWith(modules, lambda)          │   │
│  │         - Discovers @Configuration modules                           │   │
│  │         - Injects modules from @KoinApplication annotation           │   │
│  │         - A3: validates full assembled graph (compile-time safety)    │   │
│  │                                                                       │   │
│  │   Phase 3.1: validateDslDefinitionGraph() (no tree walk)             │   │
│  │     └── DSL-only A3 validation when startKoin{} exists               │   │
│  │         but no startKoin<T>() / @KoinApplication                     │   │
│  │                                                                       │   │
│  │   Phase 3.5: validatePendingCallSites() (no tree walk)               │   │
│  │     └── Validates get<T>(), inject<T>(), koinViewModel<T>()          │   │
│  │         call sites against assembled graph + DSL definitions          │   │
│  │                                                                       │   │
│  │   Phase 3.6: validateCallSiteHintsFromDependencies() (no tree walk)  │   │
│  │     └── Validates deferred call-site hints from feature modules      │   │
│  │         against all known definitions                                 │   │
│  │                                                                       │   │
│  │   Phase 4: KoinMonitorTransformer (full tree walk)                   │   │
│  │     └── Processes @Monitor annotated functions                       │   │
│  │         Wraps function bodies with trace calls                       │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                              ↓                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ PHASE 4: BYTECODE GENERATION                                         │   │
│  │   IR → .class files (JVM) / .js files (JS) / native binary           │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Why FIR + IR Separation?

- **FIR Phase**: Can only CREATE declarations (classes, functions, properties). Cannot add code bodies.
- **IR Phase**: Can MODIFY existing code, add bodies, transform calls. Cannot create new top-level declarations.

This is why `.module` property is declared in FIR but its body is filled in IR.

## Key Files Reference

### Shared Constants and Utilities

| File | Purpose |
|------|---------|
| `KoinPluginConstants.kt` | Shared option keys, definition types, hint prefixes. Single source of truth. |
| `KoinAnnotationFqNames.kt` | Centralized FqName registry for all Koin, Jakarta, and Javax annotations. |
| `PropertyValueRegistry.kt` | Stores @PropertyValue defaults for property injection. |
| `ProvidedTypeRegistry.kt` | Stores @Provided types, skipped during safety validation. |
| `KoinPluginLogger.kt` | Centralized logging with user/debug levels. Defined in `KoinPluginComponentRegistrar.kt`. |

### FIR Phase

| File | Purpose |
|------|---------|
| `KoinPluginRegistrar.kt` | Entry point for FIR. Registers `KoinModuleFirGenerator`. |
| `KoinModuleFirGenerator.kt` | Generates `.module` extension property and hint functions. |

### IR Phase

| File | Purpose |
|------|---------|
| `KoinIrExtension.kt` | Orchestrates all IR transformers in correct order (Phases 0-4 + validation phases). |
| `KoinHintTransformer.kt` | Fills bodies for FIR-generated hint functions. |
| `KoinAnnotationProcessor.kt` | Processes @Singleton/@Factory on classes, module functions, and top-level functions. Fills `.module` body. Collection only — no per-module (A1/A2) validation as of 1.1.0. |
| `KoinDSLTransformer.kt` | Transforms `single<T>()` DSL calls. Collects `DslDef` definitions and `PendingCallSiteValidation` entries. |
| `KoinStartTransformer.kt` | Transforms `startKoin<MyApp>()` calls. Runs A3 full-graph validation — the sole compile-safety verifier as of 1.1.0. |
| `KoinMonitorTransformer.kt` | Processes `@Monitor` annotated functions, wraps bodies with trace calls. |
| `CompileSafetyValidator.kt` | Orchestrates A3 full-graph safety validation (sole verifier, 1.1.0+). |
| `DefinitionCallBuilder.kt` | Builds definition call IR (`buildSingle`, `buildFactory`, etc.) for annotation-based definitions. |
| `AnnotationModels.kt` | `Definition` sealed class hierarchy (`ClassDef`, `FunctionDef`, `TopLevelFunctionDef`, `DslDef`, `ExternalFunctionDef`), plus `ModuleClass`, `DefinitionClass`, etc. |
| `QualifierExtractor.kt` | Extracts qualifier annotations (`@Named`, `@Qualifier`). Used by both DSL and annotation processors. |
| `LambdaBuilder.kt` | Creates lambda expressions with proper scope/parameter handling. |
| `ScopeBlockBuilder.kt` | Builds `scope { }` DSL blocks for scoped definitions. |
| `BindingRegistry.kt` | Compile-time safety validation engine. Matches requirements against provided types. |
| `ParameterAnalyzer.kt` | Classifies constructor/function parameters for safety validation. |
| `ConfigurationUtils.kt` | `@Configuration` annotation-presence check, used for module discovery ahead of A3 validation. |

### Cross-Phase

| File | Purpose |
|------|---------|
| `KoinConfigurationRegistry.kt` | Static registry for FIR→IR communication. **Per-JVM only!** |

## Data Structures

### KoinAnnotationProcessor

```kotlin
data class ModuleClass(
    val irClass: IrClass,
    val scanPackages: List<String>,
    val definitionFunctions: List<DefinitionFunction>,
    val includedModules: List<IrClass>
)

data class DefinitionClass(
    val irClass: IrClass,
    val definitionType: DefinitionType,  // SINGLE, FACTORY, SCOPED, VIEW_MODEL, WORKER
    val bindings: List<IrClass>,         // Auto-detected interfaces
    val scopeClass: IrClass?,            // From @Scope(MyScope::class)
    val scopeArchetype: ScopeArchetype?, // @ViewModelScope, @ActivityScope, etc.
    val createdAtStart: Boolean
)

// Top-level function definitions (discovered by @ComponentScan)
data class DefinitionTopLevelFunction(
    val irFunction: IrSimpleFunction,
    val definitionType: DefinitionType,
    val packageFqName: FqName,
    val returnTypeClass: IrClass,        // Function return type = binding type
    val bindings: List<IrClass>,
    val scopeClass: IrClass?,
    val scopeArchetype: ScopeArchetype?,
    val createdAtStart: Boolean
)

enum class DefinitionType {
    SINGLE, FACTORY, SCOPED, VIEW_MODEL, WORKER
}
```

### Definition (Unified Abstraction)

```kotlin
sealed class Definition {
    class ClassDef(...)              // @Singleton/@Factory on classes
    class FunctionDef(...)           // Functions inside @Module classes
    class TopLevelFunctionDef(...)   // Top-level annotated functions
    class DslDef(...)                // DSL definitions (single<T>, factory<T>)
    class ExternalFunctionDef(...)   // Cross-module function definitions from hints

    abstract val definitionType: DefinitionType
    abstract val returnTypeClass: IrClass
    abstract val bindings: List<IrClass>
    abstract val scopeClass: IrClass?
    abstract val scopeArchetype: ScopeArchetype?
    abstract val createdAtStart: Boolean
}
```

Used by `CompileSafetyValidator`, `BindingRegistry`, and `KoinStartTransformer` to uniformly handle
all definition sources during graph validation.

### KoinDSLTransformer

```kotlin
// Target function mapping
private val targetFunctionNames = mapOf(
    singleName to Name.identifier("buildSingle"),
    factoryName to Name.identifier("buildFactory"),
    scopedName to Name.identifier("buildScoped"),
    viewModelName to Name.identifier("buildViewModel"),
    workerName to Name.identifier("buildWorker")
)
```

## Plugin Support Architecture

### Stub Functions (What User Writes)

```kotlin
// BaseDSLExt.kt - up to 21 parameters
inline fun <reified T : Any> Module.single(): KoinDefinition<T>
inline fun <reified T : Any, reified P1> Module.single(noinline constructor: (P1) -> T): KoinDefinition<T>
// ...
```

### Target Functions (What Plugin Transforms To)

```kotlin
// ModuleExt.kt
fun <T : Any> Module.buildSingle(
    kclass: KClass<T>,
    qualifier: Qualifier?,
    definition: Definition<T>
): KoinDefinition<T>
```

### ViewModel Support (expect/actual)

```kotlin
// commonMain - expect declaration
expect abstract class ViewModel()

// jvmMain/jsMain/iosMain - actual typealias
actual typealias ViewModel = androidx.lifecycle.ViewModel

// watchosMain/tvosMain/linuxMain - stub
actual abstract class ViewModel
```

## Cross-Module Discovery

The plugin uses hint functions in `org.koin.plugin.hints` for cross-module discovery.
All hint types follow the same pattern: FIR generates the declaration, IR fills the body
and registers it as metadata-visible, downstream modules query via `symbolProvider` or
`referenceFunctions()`.

### Hint Types

| Hint prefix | Generated by | Purpose |
|---|---|---|
| `configuration_<label>` | FIR | `@Configuration` module discovery for `startKoin<T>()` |
| `definition_<type>` | FIR | Annotated class definitions (`@Singleton`, `@Factory`, etc.) |
| `definition_function_<type>` | FIR | Annotated functions inside `@Module` classes |
| `moduledef_<module>_<func>` | FIR | Per-function definitions inside `@Module` classes |
| `componentscan_<type>` | FIR | `@ComponentScan`-discovered class definitions |
| `componentscanfunc_<type>` | FIR | `@ComponentScan`-discovered top-level function definitions |
| `qualifier` | FIR | Custom `@Qualifier` annotation classes |
| `dsl_<type>` | IR (Phase 2.5) | DSL definitions (`single<T>`, `factory<T>`, etc.) |
| `callsite` | IR (Phase 3.5) | Deferred call-site validation from feature modules |

### Flow

1. **Hint Generation**: FIR or IR generates a stub function with a typed `contributed` parameter:
   ```kotlin
   fun configuration_default(contributed: MyModule): Unit = error("Stub!")
   ```

2. **Metadata Registration**: IR phase registers hints as metadata-visible:
   ```kotlin
   context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(declaration)
   ```

3. **Discovery**: Downstream modules query hints via FIR symbolProvider or IR referenceFunctions:
   ```kotlin
   session.symbolProvider.getTopLevelFunctionSymbols(
       FqName("org.koin.plugin.hints"),
       Name.identifier("configuration_default")
   )
   ```

See [PLUGIN_HINTS.md](PLUGIN_HINTS.md) for detailed documentation.

## Tree Walk Analysis

The IR phase consists of 9 sub-phases. Five perform full tree walks (visiting every IR node),
and four iterate pre-collected lists with zero tree walking.

### Full tree walks (5)

| Phase | Transformer | Purpose |
|-------|-------------|---------|
| 0 | `KoinHintTransformer` | Fill bodies for FIR-generated hint functions |
| 1 | `KoinAnnotationProcessor` | Collect annotations, fill `.module` bodies (no validation — see `docs/COMPILE_TIME_SAFETY.md`) |
| 2 | `KoinDSLTransformer` | Transform DSL calls + collect `DslDef` + collect `PendingCallSiteValidation` |
| 3 | `KoinStartTransformer` | Transform `startKoin<T>()`/`koinApplication<T>()` + A3 validation |
| 4 | `KoinMonitorTransformer` | Process `@Monitor` annotations |

### Zero-walk phases (4)

| Phase | Function | Input |
|-------|----------|-------|
| 2.5 | `generateDslDefinitionHints()` | Iterates `dslDefinitions` list from Phase 2 |
| 3.1 | `validateDslDefinitionGraph()` | Iterates `dslDefinitions` + dependency hints |
| 3.5 | `validatePendingCallSites()` | Iterates `pendingCallSites` list from Phase 2 |
| 3.6 | `validateCallSiteHintsFromDependencies()` | Queries hint functions via `referenceFunctions()` |

### Double-duty phases

- **Phase 2** does triple duty: DSL transformation + `DslDef` collection + call-site collection
- **Phase 3** does double duty: `startKoin` transformation + A3 full-graph validation

## KMP Multiplatform Handling

### Multi-Phase FIR Compilation

In KMP projects, FIR runs in separate phases for each source set:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         KMP COMPILATION PHASES                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Phase 1: commonMain                                                         │
│  ├── Sees: expect classes (e.g., expect class PlatformComponentModule)      │
│  ├── Action: Skip expect classes, generate module() for regular classes     │
│  └── Output: Synthetic files for DataModule, AppModule, etc.                │
│                                                                              │
│  Phase 2: androidMain (or other platform)                                    │
│  ├── Sees: actual classes + commonMain classes from metadata                │
│  ├── Action: Generate module() for actual classes                           │
│  └── Output: Synthetic files for PlatformComponentModule                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Unique Synthetic File Names

**Problem**: Multiple FIR phases generate to the same synthetic file name (`__GENERATED__CALLABLES__.kt`), causing overwrites.

**Solution**: Use unique file names per class:

```kotlin
// In KoinModuleFirGenerator.kt
val className = classSymbol.classId.shortClassName.asString()
val effectiveFileName = containingFile ?: "__GENERATED__${className}__Kt.kt"

createTopLevelFunction(
    Key,
    functionCallableId,
    moduleType,
    containingFileName = effectiveFileName  // Unique per class
)
```

**Result**:
- `__GENERATED__AppModule__KtKt.class` - contains `module(AppModule)`
- `__GENERATED__PlatformComponentModule__KtKt.class` - contains `module(PlatformComponentModule)`

### Expect/Actual Class Handling

```kotlin
// In KoinModuleFirGenerator.kt
private val moduleClasses: List<FirClassSymbol<*>> by lazy {
    session.predicateBasedProvider.getSymbolsByPredicate(modulePredicate)
        .filterIsInstance<FirClassSymbol<*>>()
        .filter { classSymbol ->
            // Skip expect classes - only actual classes should have module() generated
            !classSymbol.rawStatus.isExpect
        }
}
```

### K/Native Platform Detection

K/Native requires special handling for FIR-generated declarations. Synthetic files cause two issues:
1. "Source file count mismatch" errors during compilation (Kotlin < 2.3.20)
2. ObjC export crash during iOS framework linking (`NotImplementedError` in `findSourceFile`)

The plugin detects Native targets and skips synthetic function generation:

```kotlin
// In KoinModuleFirGenerator.kt
private val isNativeTarget: Boolean by lazy {
    session.moduleData.platform.isNative()
}

// In generateFunctions() - for both module() and hint functions
val containingFile = when (source) {
    is KtPsiSourceElement -> source.psi?.containingFile?.name
    else -> null  // KMP uses KtLightSourceElement, no PSI access
}

if (containingFile == null && isNativeTarget) {
    // Skip synthetic file generation for K/Native
    // Causes ObjC export failure: "An operation is not implemented" in findSourceFile
    return@mapNotNull null
}
```

**Why this works:**
- Cross-module discovery happens via the registry populated during JVM/common compilation
- `module()` functions from dependencies are already compiled in klibs
- Native targets consume, they don't need to generate hints

### Cross-Source-Set Module Resolution (IR Phase)

When `AppModule` (commonMain) includes `PlatformComponentModule` (androidMain):

```kotlin
// In KoinAnnotationProcessor.kt
private fun buildIncludesCall(...): IrExpression? {
    // Strategy 1: Find in current moduleFragment (same compilation)
    val moduleFunction = findModuleFunction(moduleFragment, includedModuleClass)
        // Strategy 2: Find via context (cross-compilation)
        ?: findModuleFunctionViaContext(includedModuleClass)
        ?: return null
    // ...
}

private fun findModuleFunctionViaContext(moduleClass: IrClass): IrSimpleFunction? {
    val packageName = moduleClass.fqNameWhenAvailable?.parent() ?: return null
    return context.referenceFunctions(
        CallableId(packageName, Name.identifier("module"))
    ).firstOrNull { func ->
        val receiverFqName = func.owner.extensionReceiverParameter?.type
            ?.classifierOrNull?.let { (it as? IrClassSymbol)?.owner?.fqNameWhenAvailable }
        receiverFqName == moduleClass.fqNameWhenAvailable
    }?.owner
}
```

### Object Module Support

Kotlin `object` modules use `irGetObject` instead of constructor calls:

```kotlin
val instanceExpression = if (includedModuleClass.isObject) {
    builder.irGetObject(includedModuleClass.symbol)
} else {
    val constructor = includedModuleClass.primaryConstructor ?: return null
    builder.irCallConstructor(constructor.symbol, emptyList())
}
```

## Known Limitations

### Kotlin Version Compatibility

Compiler plugins are NOT binary compatible across minor versions. Plugin compiled with 2.2.x won't work with 2.3.x.

See [ROADMAP.md](ROADMAP.md#multi-kotlin-version-compatibility-deferred) for multi-version strategies.
