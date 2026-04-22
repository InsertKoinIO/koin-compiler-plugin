# Koin Compiler Plugin - Complete Development & Debugging Guide

This document provides everything you need to understand, debug, and develop the Koin Compiler Plugin.

---

## Table of Contents

1. [Project Architecture](#1-project-architecture)
2. [Compilation Flow](#2-compilation-flow)
3. [Key Files Reference](#3-key-files-reference)
4. [Enabling Debug Logging](#4-enabling-debug-logging)
5. [Transformation Examples](#5-transformation-examples)
6. [Running Tests](#6-running-tests)
7. [Common Issues & Debugging](#7-common-issues--debugging)
8. [Development Workflow](#8-development-workflow)
9. [IR Inspection Techniques](#9-ir-inspection-techniques)
10. [Cross-Module Discovery (Limitations)](#10-cross-module-discovery-limitations)
11. [Useful Commands Cheatsheet](#11-useful-commands-cheatsheet)

---

## 1. Project Architecture

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
    │   └── src/
    │       ├── jvmMain/                # Main source code
    │       └── jvmTest/                # Tests
    └── sample-feature-module/          # Multi-module test
```

### Plugin Registration Chain

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

---

## 2. Compilation Flow

Understanding the compilation flow is **critical** for debugging:

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
│  │      - fun org.koin.plugin.hints.configuration_default(): Nothing    │   │
│  │   3. Populates KoinConfigurationRegistry with module names           │   │
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
│  │   Sub-phase 0: KoinHintTransformer                                 │   │
│  │     └── Fills bodies for FIR-generated hint functions                │   │
│  │         - getConfigurationModuleClasses() → listOf("module.fqn")     │   │
│  │         - hint functions → error("never call")                       │   │
│  │                                                                       │   │
│  │   Sub-phase 1: KoinAnnotationProcessor                               │   │
│  │     └── Scans @Singleton/@Factory/@KoinViewModel classes             │   │
│  │     └── FILLS BODY of FIR-generated .module property:                │   │
│  │         val MyModule.module = module {                               │   │
│  │             buildSingle(A::class, null) { A(get()) }                 │   │
│  │             buildFactory(B::class, null) { B(get(), get()) }         │   │
│  │         }                                                             │   │
│  │                                                                       │   │
│  │   Sub-phase 2: KoinDSLTransformer                             │   │
│  │     └── Transforms DSL calls:                                        │   │
│  │         single<T>() → buildSingle(T::class, null) { T(get()) }       │   │
│  │         scope.create(::T) → T(scope.get(), scope.get())              │   │
│  │                                                                       │   │
│  │   Sub-phase 3: KoinStartTransformer                              │   │
│  │     └── Transforms app entry points:                                 │   │
│  │         startKoin<MyApp>() → startKoinWith(modules, lambda)          │   │
│  │         - Discovers @Configuration modules                           │   │
│  │         - Injects modules from @KoinApplication annotation           │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                              ↓                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │ PHASE 4: BYTECODE GENERATION                                         │   │
│  │   IR → .class files (JVM) / .js files (JS) / native binary           │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Why FIR + IR Separation?

- **FIR Phase**: Can only CREATE declarations (classes, functions, properties). Cannot add code bodies.
- **IR Phase**: Can MODIFY existing code, add bodies, transform calls. Cannot create new top-level declarations.

This is why `.module` property is declared in FIR but its body is filled in IR.

---

## 3. Key Files Reference

### 3.1 FIR Phase Files

#### `KoinPluginRegistrar.kt`
**Location**: `koin-compiler-plugin/src/org/koin/compiler/plugin/KoinPluginRegistrar.kt`

```kotlin
class KoinPluginRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::KoinModuleFirGenerator  // Register our FIR extension
    }
}
```

**Purpose**: Entry point for FIR phase. Registers `KoinModuleFirGenerator`.

---

#### `KoinModuleFirGenerator.kt`
**Location**: `koin-compiler-plugin/src/org/koin/compiler/plugin/fir/KoinModuleFirGenerator.kt`

**Purpose**: Generates declarations during FIR phase.

**Key Data Structures**:
```kotlin
// Predicate to find @Module annotated classes
private val modulePredicate = LookupPredicate.create { annotated(MODULE_ANNOTATION) }

// Cached module classes (lazy evaluation)
private val moduleClasses: List<FirClassSymbol<*>> by lazy { ... }

// Cached @Configuration modules
private val configurationModules: List<FirClassSymbol<*>> by lazy { ... }
```

**Key Methods**:

| Method | Purpose |
|--------|---------|
| `registerPredicates()` | Registers `@Module` annotation for lookup |
| `getTopLevelCallableIds()` | Returns CallableIds to generate (properties + functions) |
| `generateProperties()` | Creates `val T.module: Module` extension property |
| `generateFunctions()` | Creates hint functions in `org.koin.plugin.hints` |
| `discoverModulesFromHintsIfNeeded()` | Queries symbolProvider for hint functions in dependencies |

**What Gets Generated**:

For a class:
```kotlin
@Module @ComponentScan @Configuration
class MyModule
```

FIR generates:
1. `val MyModule.module: Module` (extension property, no body)
2. `fun org.koin.plugin.hints.configuration_default(contributed: MyModule): Unit` (hint function for cross-module discovery)

---

### 3.2 IR Phase Files

#### `KoinIrExtension.kt`
**Location**: `koin-compiler-plugin/src/org/koin/compiler/plugin/ir/KoinIrExtension.kt`

**Purpose**: Orchestrates all IR transformations in correct order.

```kotlin
override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    // Phase 0: Generate bodies for FIR-generated functions
    moduleFragment.transform(KoinHintTransformer(pluginContext), null)

    // Phase 1: Process annotations, fill .module property bodies
    val annotationProcessor = KoinAnnotationProcessor(pluginContext, messageCollector)
    annotationProcessor.collectAnnotations(moduleFragment)
    annotationProcessor.generateModuleExtensions(moduleFragment)

    // Phase 2: Transform single<T>() / create(::T) calls
    moduleFragment.transform(KoinDSLTransformer(pluginContext, messageCollector), null)

    // Phase 3: Transform startKoin<T>() calls
    moduleFragment.transform(KoinStartTransformer(pluginContext, moduleFragment, messageCollector), null)
}
```

**Order matters!** Each transformer depends on the previous one's output.

---

#### `KoinAnnotationProcessor.kt`
**Location**: `koin-compiler-plugin/src/org/koin/compiler/plugin/ir/KoinAnnotationProcessor.kt`

**Purpose**: Processes `@Singleton`, `@Factory`, `@KoinViewModel`, `@Scoped` annotations.

**Key Data Structures**:
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

enum class DefinitionType {
    SINGLE, FACTORY, SCOPED, VIEW_MODEL, WORKER
}
```

**Key Methods**:

| Method | Purpose |
|--------|---------|
| `collectAnnotations()` | Visits all classes, collects annotated ones |
| `processClass()` | Checks if class has @Module or @Singleton/etc |
| `generateModuleExtensions()` | Fills body of FIR-generated .module property |
| `buildClassDefinitionCall()` | Creates `buildSingle(T::class, ...) { T(get()) }` |
| `createDefinitionLambda()` | Creates the `{ scope, params -> T(get(), get()) }` lambda |
| `generateKoinArgumentForParameter()` | Decides: `get()`, `getOrNull()`, `inject()`, `getProperty()` |

**Annotation Detection Logic**:
```kotlin
private fun getDefinitionType(declaration: IrDeclaration): DefinitionType? {
    return when {
        declaration.hasAnnotation(singletonFqName) -> DefinitionType.SINGLE
        declaration.hasAnnotation(singleFqName) -> DefinitionType.SINGLE
        declaration.hasAnnotation(factoryFqName) -> DefinitionType.FACTORY
        declaration.hasAnnotation(scopedFqName) -> DefinitionType.SCOPED
        declaration.hasAnnotation(koinViewModelFqName) -> DefinitionType.VIEW_MODEL
        declaration.hasAnnotation(koinWorkerFqName) -> DefinitionType.WORKER
        // JSR-330 support
        declaration.hasAnnotation(jakartaSingletonFqName) -> DefinitionType.SINGLE
        declaration.hasAnnotation(jakartaInjectFqName) -> DefinitionType.FACTORY
        else -> null
    }
}
```

---

#### `KoinDSLTransformer.kt`
**Location**: `koin-compiler-plugin/src/org/koin/compiler/plugin/ir/KoinDSLTransformer.kt`

**Purpose**: Transforms `single<T>()`, `factory<T>()`, `scope.create(::T)` calls.

**Key Methods**:

| Method | Purpose |
|--------|---------|
| `visitCall()` | Entry point - checks if call matches our patterns |
| `handleTypeParameterCall()` | Handles `single<T>()` → `buildSingle(T::class, ...)` |
| `handleScopeCreate()` | Handles `scope.create(::T)` → `T(scope.get(), ...)` |
| `findTargetFunction()` | Finds the target function (buildSingle, buildFactory, etc.) |
| `createDefinitionLambda()` | Creates `{ scope, params -> T(get(), get()) }` |

**Target Function Mapping**:
```kotlin
private val targetFunctionNames = mapOf(
    singleName to Name.identifier("buildSingle"),
    factoryName to Name.identifier("buildFactory"),
    scopedName to Name.identifier("buildScoped"),
    viewModelName to Name.identifier("buildViewModel"),
    workerName to Name.identifier("buildWorker")
)
```

**Matching Logic in visitCall()**:
```kotlin
// Must be one of our function names
if (functionName != createName && functionName != singleName && ...) {
    return transformedCall
}

// Receiver must be from Koin package
val receiverPackage = receiverClassifier.packageFqName?.asString()
if (!receiverPackage.startsWith("org.koin.core") &&
    !receiverPackage.startsWith("org.koin.dsl")) {
    return transformedCall
}

// For type parameter syntax: single<T>()
if (transformedCall.valueArgumentsCount == 0 &&
    transformedCall.typeArgumentsCount >= 1 &&
    extensionReceiver != null) {
    return handleTypeParameterCall(...)
}
```

---

#### `KoinStartTransformer.kt`
**Location**: `koin-compiler-plugin/src/org/koin/compiler/plugin/ir/KoinStartTransformer.kt`

**Purpose**: Transforms `startKoin<MyApp>()` to inject modules.

**Key Methods**:

| Method | Purpose |
|--------|---------|
| `visitCall()` | Detects startKoin/koinApplication calls |
| `extractModulesFromKoinApplicationAnnotation()` | Gets modules from @KoinApplication |
| `extractExplicitModules()` | Gets modules from `modules = [...]` parameter |
| `discoverLocalConfigurationModules()` | Scans current compilation for @Configuration |
| `discoverModulesFromHints()` | Tries to find modules from dependencies |
| `buildModuleGetCall()` | Creates `MyModule().module` expression |

**Discovery Strategies**:
```kotlin
private fun discoverModulesFromHints(): List<IrClass> {
    // Strategy 1: Local hints from moduleFragment
    for (file in moduleFragment.files) {
        if (file.packageFqName == hintsPackage) { ... }
    }

    // Strategy 2: Query via IR (limited - can't enumerate)
    // Note: IR cannot enumerate package contents from dependencies

    // Strategy 3: In-memory registry (same compilation only)
    val registryClassNames = KoinConfigurationRegistry.getAllModuleClassNames()
    for (moduleClassName in registryClassNames) {
        val classId = ClassId.topLevel(FqName(moduleClassName))
        val moduleClass = context.referenceClass(classId)?.owner
        // ...
    }
}
```

---

#### `KoinHintTransformer.kt`
**Location**: `koin-compiler-plugin/src/org/koin/compiler/plugin/ir/KoinHintTransformer.kt`

**Purpose**: Fills bodies for FIR-generated hint functions.

```kotlin
override fun visitSimpleFunction(declaration: IrSimpleFunction): IrSimpleFunction {
    if (fqName?.parent() == hintsPackage && declaration.body == null) {
        if (declaration.name == registryFunctionName) {
            // Generate: return listOf("module1.fqn", "module2.fqn")
            declaration.body = generateRegistryFunctionBody(declaration)
        } else {
            // Generate: throw error("Hint function - never call")
            declaration.body = generateHintFunctionBody(declaration)
        }
    }
    return declaration
}
```

---

#### `KoinConfigurationRegistry.kt`
**Location**: `koin-compiler-plugin/src/org/koin/compiler/plugin/KoinConfigurationRegistry.kt`

**Purpose**: Cross-phase communication between FIR and IR.

```kotlin
object KoinConfigurationRegistry {
    private val localModuleClassNames = mutableSetOf<String>()
    private val jarModuleClassNames = mutableSetOf<String>()

    fun registerLocalModule(moduleClassName: String) { ... }
    fun registerJarModule(moduleClassName: String) { ... }
    fun getLocalModuleClassNames(): Set<String> { ... }
    fun getAllModuleClassNames(): Set<String> { ... }
    fun clear() { ... }
}
```

**CRITICAL LIMITATION**: This registry is per-JVM instance. Each Gradle compilation task runs in a separate context, so registry is NOT shared across compilation tasks!

---

## 4. Enabling Debug Logging

### 4.1 Gradle Configuration

Enable logging via the `koinCompiler` extension in your `build.gradle.kts`:

```kotlin
koinCompiler {
    userLogs = true           // Component detection logs (what's being processed)
    debugLogs = true          // Internal processing logs (verbose)
    unsafeDslChecks = true    // Validates create() is the only instruction in lambda (default: true)
    skipDefaultValues = true  // Skip injection for parameters with default values (default: true)
}
```

### 4.2 Viewing Logs

**View logs during compilation**:
```bash
cd test-apps
./gradlew :sample-app:compileKotlinJvm 2>&1 | grep "\[Koin"
```

**Log prefixes**:
| Prefix | Phase | Level |
|--------|-------|-------|
| `[Koin]` | IR | User |
| `[Koin-Debug]` | IR | Debug |
| `[Koin-FIR]` | FIR | User |
| `[Koin-Debug-FIR]` | FIR | Debug |

### 4.3 Example Output

With `userLogs = true`:
```
w: [Koin] @Module/@ComponentScan on class MyModule
w: [Koin]   Scanning packages: examples.annotations
w: [Koin] @Singleton on class MyService
w: [Koin]   @Named("production")
w: [Koin] Intercepting single<MyClass>() on Module
w: [Koin]   Skipping injection for parameter 'timeout' - using default value
w: [Koin] Intercepting startKoin<MyApp>()
w: [Koin]   -> Injecting modules: examples.annotations.MyModule
w: [Koin-FIR] Found 1 @Configuration modules
```

With `debugLogs = true` (additional verbose output):
```
w: [Koin-Debug-FIR] Looking for @Configuration modules among 2 @Module classes
w: [Koin-Debug-FIR]   -> examples.annotations.MyModule: @Configuration=true
w: [Koin-Debug-FIR] Adding 1 hint functions to getTopLevelCallableIds()
w: [Koin-Debug] visitCall: org.koin.plugin.module.dsl.single | args=0 | typeArgs=1
w: [Koin-Debug] Creating definition lambda for MyService
```

---

## 5. Transformation Examples

### 5.1 DSL Syntax: `single<T>()`

**Input** (user code):
```kotlin
val myModule = module {
    single<MyService>()
}
```

**Matched by**: `KoinDSLTransformer.handleTypeParameterCall()`

**Output** (after transformation):
```kotlin
val myModule = module {
    buildSingle(MyService::class, null) { scope, params ->
        MyService(scope.get(), scope.getOrNull())
    }
}
```

### 5.2 DSL Syntax: `scope.create(::T)`

**Input**:
```kotlin
koin.scope.create(::MyService)
```

**Matched by**: `KoinDSLTransformer.handleScopeCreate()`

**Output**:
```kotlin
MyService(koin.scope.get(), koin.scope.get())
```

### 5.3 Annotation: `@Singleton`

**Input**:
```kotlin
@Module @ComponentScan
class MyModule

@Singleton
class MyService(val repo: Repository, val logger: Logger?)
```

**Processed by**: `KoinAnnotationProcessor`

**Generated** (fills FIR-generated property body):
```kotlin
val MyModule.module: Module get() = module {
    buildSingle(MyService::class, null) { scope, params ->
        MyService(scope.get(), scope.getOrNull())
    }
}
```

### 5.4 Annotation: `@Named` on Class

**Input**:
```kotlin
@Singleton
@Named("production")
class ProductionService : Service
```

**Output**:
```kotlin
buildSingle(ProductionService::class, named("production")) { scope, params ->
    ProductionService()
}.bind(Service::class)
```

### 5.5 Annotation: `@Named` on Parameter

**Input**:
```kotlin
@Singleton
class Consumer(@Named("production") val service: Service)
```

**Output**:
```kotlin
buildSingle(Consumer::class, null) { scope, params ->
    Consumer(scope.get(named("production")))
}
```

### 5.6 Annotation: `@InjectedParam`

**Input**:
```kotlin
@Factory
class MyClass(@InjectedParam val id: Int, val service: Service)
```

**Output**:
```kotlin
buildFactory(MyClass::class, null) { scope, params ->
    MyClass(params.get(), scope.get())
}
```

Usage: `koin.get<MyClass> { parametersOf(42) }`

### 5.7 Annotation: `@Property`

**Input**:
```kotlin
@Singleton
class Config(@Property("server.url") val serverUrl: String)
```

**Output**:
```kotlin
buildSingle(Config::class, null) { scope, params ->
    Config(scope.getProperty("server.url"))
}
```

### 5.8 Nullable Parameters

**Input**:
```kotlin
@Singleton
class MyService(val required: A, val optional: B? = null)
```

**Output**:
```kotlin
buildSingle(MyService::class, null) { scope, params ->
    MyService(scope.get(), scope.getOrNull())
}
```

### 5.9 Lazy Parameters

**Input**:
```kotlin
@Singleton
class MyService(val lazyDep: Lazy<HeavyService>)
```

**Output**:
```kotlin
buildSingle(MyService::class, null) { scope, params ->
    MyService(scope.inject())
}
```

### 5.10 List Parameters

**Input**:
```kotlin
@Singleton
class Aggregator(val handlers: List<Handler>)
```

**Output**:
```kotlin
buildSingle(Aggregator::class, null) { scope, params ->
    Aggregator(scope.getAll())
}
```

### 5.11 startKoin Transformation

**Input**:
```kotlin
@KoinApplication(modules = [MyModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {
        printLogger()
    }
}
```

**Output**:
```kotlin
fun main() {
    startKoinWith(listOf(MyModule().module)) {
        printLogger()
    }
}
```

---

## 6. Running Tests

### 6.1 Compiler Plugin Unit Tests

```bash
./gradlew :koin-compiler-plugin:test
```

Tests use Kotlin's internal test framework with `.kt` files in `testData/`.

### 6.2 Sample App Tests

```bash
cd test-apps
./gradlew :sample-app:jvmTest
```

### 6.3 Run Specific Test

```bash
cd test-apps
./gradlew :sample-app:jvmTest --tests "examples.annotations.AnnotationsConfigTest"
./gradlew :sample-app:jvmTest --tests "examples.DSLTest"
```

### 6.4 Run Sample App

```bash
cd test-apps
./gradlew :sample-app:jvmRun
```

### 6.5 Test with Verbose Output

```bash
cd test-apps
./gradlew :sample-app:jvmTest --info 2>&1 | grep -E "(PASSED|FAILED|Koin-Plugin)"
```

### 6.6 Update Test Data (for compiler tests)

```bash
./gradlew :koin-compiler-plugin:test -Pupdate.testdata=true
```

---

## 7. Common Issues & Debugging

### 7.1 "No modules to inject"

**Symptom**: `startKoin<MyApp>()` logs "No modules to inject"

**Debug Steps**:
1. Check `@KoinApplication` annotation:
   ```kotlin
   @KoinApplication(modules = [MyModule::class])  // Explicit is best
   object MyApp
   ```

2. Check compilation logs for hint generation:
   ```bash
   ./gradlew :sample-app:compileKotlinJvm 2>&1 | grep "Configuration"
   ```

3. Verify modules are in same compilation unit (cross-module discovery is limited)

### 7.2 ".module property not generated"

**Symptom**: `MyModule().module` doesn't compile

**Debug Steps**:
1. Verify class has BOTH annotations:
   ```kotlin
   @Module        // Required
   @ComponentScan // Required
   class MyModule
   ```

2. Check compilation logs:
   ```bash
   ./gradlew :sample-app:compileKotlinJvm 2>&1 | grep "getTopLevelCallableIds"
   ```

3. For KMP projects, if you encounter issues, try disabling incremental compilation:
   ```properties
   # gradle.properties
   kotlin.incremental.multiplatform=false
   ```

### 7.3 "Transformation not happening"

**Symptom**: `single<T>()` not transformed, runtime error

**Debug Steps**:
1. Add log in `visitCall()`:
   ```kotlin
   log("visitCall: ${callee.fqNameWhenAvailable} receiver=${receiver.type}")
   ```

2. Check receiver type is from Koin:
   ```bash
   ./gradlew :sample-app:compileKotlinJvm 2>&1 | grep "visitCall"
   ```

3. Verify import is correct:
   ```kotlin
   import org.koin.dsl.module
   import org.koin.core.module.dsl.single  // NOT org.koin.dsl.single
   ```

### 7.4 "Wrong get() calls generated"

**Symptom**: Missing qualifiers, nullable not handled

**Debug Steps**:
1. Check `generateKoinArgumentForParameter()` in logs:
   ```kotlin
   log("Generating arg for ${param.name}: type=${param.type}, nullable=${param.type.isMarkedNullable()}")
   ```

2. Verify annotation FQNames:
   ```kotlin
   log("Annotations on ${param.name}: ${param.annotations.map { it.type.classFqName }}")
   ```

### 7.5 "IrFile mismatch" on Native Targets

**Symptom**: Compilation fails on iOS/macOS with file mismatch

**Cause**: FIR generators running on wrong source sets

**Fix**: Already applied - hint generation only when `configurationModules.isNotEmpty()`

### 7.6 "Empty hints file overwrites real one"

**Symptom**: Hint functions disappear, cross-module discovery fails

**Cause**: Multiple FIR sessions (commonMain, jvmMain) writing to same hints package

**Fix**: Check in `getTopLevelCallableIds()`:
```kotlin
if (configurationModules.isNotEmpty()) {
    // Generate hints
} else if (moduleClasses.isEmpty()) {
    // Skip - empty compilation
} else {
    // Has @Module but no @Configuration - just trigger
}
```

### 7.7 KMP: "NoSuchMethodError: module()" at Runtime

**Symptom**: Runtime error like `java.lang.NoSuchMethodError: No static method module(PlatformComponentModule)`

**Cause**: Multiple FIR phases (commonMain, androidMain) generating to same synthetic file name, later phases overwriting earlier ones.

**Debug Steps**:
1. Check FIR logs for duplicate file generation:
   ```bash
   ./gradlew :composeApp:assembleDebug 2>&1 | grep "GENERATED"
   ```
   Look for multiple classes generating to `__GENERATED__CALLABLES__Kt.kt`

2. Check which classes are in the generated file:
   ```bash
   javap -p build/tmp/kotlin-classes/debug/com/example/__GENERATED__CALLABLES__Kt.class
   ```

3. Verify unique file names are used:
   ```bash
   ls build/tmp/kotlin-classes/debug/com/example/__GENERATED__*
   ```
   Should see: `__GENERATED__AppModule__KtKt.class`, `__GENERATED__PlatformComponentModule__KtKt.class`, etc.

**Fix**: Already implemented - uses unique file names per class: `__GENERATED__${className}__Kt.kt`

### 7.8 KMP: "expect class" getting module() generated

**Symptom**: Compilation error or "no body for FIR-generated function" for expect classes

**Cause**: FIR generator not filtering out expect classes

**Debug Steps**:
1. Check FIR logs for expect class handling:
   ```bash
   ./gradlew :composeApp:assembleDebug 2>&1 | grep -E "(expect|Skipping)"
   ```
   Should see: `Skipping expect class: com/example/PlatformComponentModule`

2. Verify `rawStatus.isExpect` check in `KoinModuleFirGenerator.kt`

**Fix**: Already implemented - expect classes are filtered:
```kotlin
.filter { classSymbol -> !classSymbol.rawStatus.isExpect }
```

### 7.9 KMP: "Source file count mismatch" on K/Native

**Symptom**: K/Native compilation fails with "The number of source files (X) does not match the number of IrFiles (Y)"

**Cause**: FIR generating synthetic files for metadata classes on K/Native targets

**Debug Steps**:
1. Check FIR logs for K/Native skipping:
   ```bash
   ./gradlew :composeApp:compileKotlinIosArm64 2>&1 | grep "K/Native"
   ```
   Should see: `Skipping module() for ... (from metadata, K/Native)`

2. Verify platform detection:
   ```kotlin
   val isNativeTarget = session.moduleData.platform.isNative()
   ```

**Fix**: Already implemented - synthetic file generation skipped on K/Native

### 7.10 KMP: "module() not found for included module"

**Symptom**: IR phase can't find module() function for a module in @Module(includes = [...])

**Cause**: Module is from different source set (e.g., androidMain including commonMain module)

**Debug Steps**:
1. Check IR logs for module resolution:
   ```bash
   ./gradlew :composeApp:assembleDebug 2>&1 | grep "Found module()"
   ```

2. Look for cross-source-set lookup:
   ```
   Found module() in moduleFragment for DataModule  # Same source set
   Found module() via context for PlatformComponentModule  # Cross source set
   ```

**Fix**: Already implemented - uses `findModuleFunctionViaContext()` fallback for cross-compilation lookup

### 7.11 KMP: Object module causing SyntheticAccessorLowering error

**Symptom**: Compilation fails with error about synthetic accessors for Kotlin `object`

**Cause**: Code calling constructor on a Kotlin `object` (singleton) instead of using the instance

**Debug Steps**:
1. Check if the module is defined as `object`:
   ```kotlin
   @Module
   object MyModule  // Object - should use MyModule.INSTANCE
   ```

2. Verify `isObject` check in IR:
   ```bash
   ./gradlew :composeApp:assembleDebug 2>&1 | grep "object"
   ```

**Fix**: Already implemented - uses `irGetObject()` for object modules:
```kotlin
val instanceExpression = if (includedModuleClass.isObject) {
    builder.irGetObject(includedModuleClass.symbol)
} else {
    builder.irCallConstructor(constructor.symbol, emptyList())
}
```

### 7.12 IntelliJ: Class symbols showing as red (unresolved)

**Symptom**: After updating the plugin, IntelliJ shows class symbols (like `A`, `B`, `C`, etc.) as red/unresolved in `test-apps` or other projects using the plugin.

**Cause**: IntelliJ has cached the old plugin version and hasn't picked up the newly installed one from Maven Local.

**Fix Steps** (in order of least to most aggressive):

1. **Reinstall plugin to Maven Local**:
   ```bash
   ./install.sh
   ```

2. **Refresh Gradle in IntelliJ**:
   - Open the Gradle tool window (View → Tool Windows → Gradle)
   - Click the refresh button (🔄 icon)

3. **Invalidate IntelliJ caches**:
   - Go to `File` → `Invalidate Caches...`
   - Check "Clear file system cache and Local History"
   - Click `Invalidate and Restart`

4. **Clean and reimport project** (last resort):
   ```bash
   cd test-apps
   rm -rf .gradle build */build .idea/*.xml
   ```
   Then reopen the project in IntelliJ and let it reimport.

**Prevention**: When actively developing the plugin, consider keeping `test-apps` in a separate IntelliJ window and refreshing Gradle after each `./install.sh`.

### 7.13 "'buildViewModel' is not on classpath. Add dependency: io.insert-koin:koin-core-viewmodel"

**Symptom**: Compile error at an `@KoinViewModel`-annotated class:
```
[Koin] @KoinViewModel definition 'com.app.MyViewModel' cannot be generated:
  'buildViewModel' is not on classpath. Add dependency: io.insert-koin:koin-core-viewmodel
```

Same shape for `@KoinWorker` → `io.insert-koin:koin-android-workmanager`.

**Cause**: `@KoinViewModel` / `@KoinWorker` annotations live in `koin-annotations` (always available via `koin-core`), but the runtime DSL that backs them (`Module.buildViewModel` / `Module.buildWorker`) ships in a separate artifact. If you only have `koin-core` + `koin-annotations`, the plugin can't generate a working definition for those annotations.

Before the check existed (pre-RC2.3), the plugin silently skipped the definition; you got `NoDefinitionFoundException` at runtime. RC2.3+ fails loudly with the fix instruction.

**Fix**:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.insert-koin:koin-core-viewmodel:4.2.1")   // for @KoinViewModel
    implementation("io.insert-koin:koin-android-workmanager:4.2.1") // for @KoinWorker (Android)
}
```

The error fires once per annotation type per compilation — if you see it for `@KoinViewModel`, all ViewModel definitions in that compilation are blocked until the artifact is added.

---

## 8. Development Workflow

### 8.1 Making Changes

```bash
# 1. Edit compiler-plugin code
# e.g., koin-compiler-plugin/src/org/koin/compiler/plugin/ir/KoinDSLTransformer.kt

# 2. Publish to Maven Local
./install.sh

# 3. Test changes
cd test-apps
./gradlew clean :sample-app:jvmTest
```

### 8.2 Full Rebuild Cycle

```bash
# From project root
./install.sh && cd test-apps && ./gradlew clean :sample-app:jvmTest
```

### 8.3 Quick Iteration (Skip Clean)

```bash
cd test-apps
./gradlew :sample-app:compileKotlinJvm 2>&1 | grep "\[Koin-Plugin\]"
```

### 8.4 Adding a New Transformation

1. **Identify the phase**: FIR (new declarations) or IR (transform code)?

2. **For IR transformations**:
   - Add to appropriate transformer (`KoinDSLTransformer`, `KoinAnnotationProcessor`)
   - Pattern match in `visitCall()` or `visitClass()`
   - Create the transformed IR using `DeclarationIrBuilder`

3. **For FIR declarations**:
   - Add to `KoinModuleFirGenerator`
   - Return new CallableId in `getTopLevelCallableIds()`
   - Generate in `generateProperties()` or `generateFunctions()`

4. **Add target function** (if needed):
   - Add to `plugin-support/src/commonMain/kotlin/org/koin/plugin/module/dsl/`
   - Create stub function (what user writes) and target function (what plugin transforms to)
   - Note: `plugin-support` is included in Koin (`koin-annotations`), coordinate with Koin releases

---

## 9. IR Inspection Techniques

### 9.1 Use Built-in Debug Logging

The recommended approach is to enable `debugLogs` in your Gradle configuration:

```kotlin
koinCompiler {
    debugLogs = true
}
```

This provides detailed output about IR transformations without modifying plugin source code.

### 9.2 Inspect Bytecode

```bash
# After compilation
cd test-apps/sample-app/build/classes/kotlin/jvm/main

# Decompile a class
javap -c -p examples/annotations/MyModule.class

# View all hints
javap -c org/koin/compiler/hints/*.class
```

### 9.3 Use IntelliJ Debugger

1. Open `compiler-plugin` in IntelliJ
2. Set breakpoint in any transformer
3. Run test with debugger:
   ```bash
   ./gradlew :koin-compiler-plugin:test --debug-jvm
   ```
4. Connect IntelliJ debugger to port 5005

---

## 10. Cross-Module Discovery (Limitations)

### 10.1 The Problem

When compiling Module B that depends on Module A (already compiled JAR):
- FIR can query `symbolProvider.getTopLevelFunctionSymbols()` across JARs
- IR CANNOT enumerate package contents from dependencies

### 10.2 Current Implementation

**FIR Phase** (`KoinModuleFirGenerator.kt`):
```kotlin
fun discoverModulesFromHintsIfNeeded() {
    val callableNames = session.symbolProvider.symbolNamesProvider
        .getTopLevelCallableNamesInPackage(HINTS_PACKAGE)

    for (name in callableNames) {
        val funcSymbols = session.symbolProvider
            .getTopLevelFunctionSymbols(HINTS_PACKAGE, name)
        // Extract module class from parameter type
    }
}
```

**IR Phase** (`KoinStartTransformer.kt`):
```kotlin
fun discoverModulesFromHints(): List<IrClass> {
    // Strategy 1: Local hints (same compilation)
    // Strategy 2: IR query (limited)
    // Strategy 3: Registry (same JVM only)
}
```

### 10.3 Known Limitations

1. **Registry is per-JVM**: Each Gradle task runs separately
2. **IR can't enumerate**: Must know exact function names
3. **Empty compilations overwrite**: Fixed by checking `configurationModules.isNotEmpty()`

### 10.4 Recommended Workaround

Use explicit module specification:
```kotlin
@KoinApplication(modules = [ModuleA::class, ModuleB::class])
object MyApp
```

### 10.5 Metadata Registration for Cross-Module Discovery

The plugin uses metadata registration for cross-module visibility:
```kotlin
pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(function)
```

This makes FIR-generated functions visible in downstream IR phases.

---

## 11. Useful Commands Cheatsheet

### Build Commands

```bash
# Build plugin and publish to Maven Local
./install.sh

# Build plugin only (no publish)
./gradlew :koin-compiler-plugin:build

# Clean everything
./gradlew clean
cd test-apps && ./gradlew clean
```

### Test Commands

```bash
# Plugin unit tests
./gradlew :koin-compiler-plugin:test

# Sample app tests
cd test-apps && ./gradlew :sample-app:jvmTest

# Specific test
./gradlew :sample-app:jvmTest --tests "examples.annotations.AnnotationsConfigTest"

# Run sample
./gradlew :sample-app:jvmRun
```

### Debug Commands

```bash
# View plugin logs during compilation
./gradlew :sample-app:compileKotlinJvm 2>&1 | grep "\[Koin-Plugin\]"

# View FIR logs (enable debugLogs in koinCompiler config)
./gradlew :sample-app:compileKotlinJvm 2>&1 | grep "\[Koin"

# Decompile generated bytecode
javap -c -p build/classes/kotlin/jvm/main/examples/annotations/MyModule.class

# List generated hints
ls -la build/classes/kotlin/jvm/main/org/koin/compiler/hints/
```

### Iteration Commands

```bash
# Quick rebuild + test
./install.sh && cd test-apps && ./gradlew :sample-app:jvmTest

# Compile only (faster)
cd test-apps && ./gradlew :sample-app:compileKotlinJvm

# Force clean rebuild
cd test-apps && ./gradlew clean :sample-app:compileKotlinJvm --no-build-cache
```

### KMP Commands

```bash
# iOS compilation
cd test-apps && ./gradlew :sample-app:compileKotlinIosSimulatorArm64

# All native targets
./gradlew :sample-app:compileKotlinNative

# JS compilation
./gradlew :sample-app:compileKotlinJs
```

---

## Appendix: File Quick Reference

| File | Phase | Purpose |
|------|-------|---------|
| `KoinPluginComponentRegistrar.kt` | - | Plugin entry point |
| `KoinPluginRegistrar.kt` | FIR | Registers FIR extensions |
| `KoinModuleFirGenerator.kt` | FIR | Generates .module property + hints |
| `KoinConfigurationRegistry.kt` | FIR→IR | Cross-phase communication |
| `KoinIrExtension.kt` | IR | Orchestrates IR transformers |
| `KoinHintTransformer.kt` | IR-0 | Fills hint function bodies |
| `KoinAnnotationProcessor.kt` | IR-1 | Processes @Singleton etc |
| `KoinDSLTransformer.kt` | IR-2 | Transforms single<T>() |
| `KoinStartTransformer.kt` | IR-3 | Transforms startKoin<T>() |

---

*Last updated: 2026-02-02*
