# Plan: Module-Scoped Component Scan Hints

## Problem

When a `@Module @ComponentScan @Configuration` class is in Gradle module A, and a `@Configuration` sibling is in Gradle module B, module B **can't see** what module A's `@ComponentScan` discovered.

FIR only generates hint functions for "orphan" definitions (not covered by any local `@ComponentScan`). Definitions covered by local scan get no hints → invisible cross-module.

**Example:**
```
── Gradle module A ──
    @Singleton class Repository          ← in com.example.core

    @Module @ComponentScan @Configuration("prod")
    class CoreModule                     ← scans com.example.core, finds Repository
                                         ← FIR: Repository is "covered by local scan" → NO hint generated

── Gradle module B (depends on A) ──
    @Singleton class Service(val repo: Repository)

    @Module @ComponentScan @Configuration("prod")
    class ServiceModule                  ← sibling of CoreModule
                                         ← tries to validate: needs Repository
                                         ← can't see Repository → false positive error or deferral
```

## Solution

Generate **module-scoped scan hints** in FIR that export what each module's `@ComponentScan` found. Every module becomes self-describing. Cross-module consumers query these hints to get the full picture.

## Current Flow (with deferrals)

```
Phase 1: collectAnnotations()        → finds all @Module, @Singleton, etc.
          generateModuleExtensions()  → for EACH module, one by one:
            1. collect its definitions
            2. validate (A2) ← tries to see siblings, may DEFER
            3. generate .module() IR code
Phase 3: startKoin transform          → A3: re-validates deferred modules
```

## Proposed Flow (consolidate first, validate locally)

```
Step 1: Collect ALL module definitions (already done in collectAnnotations)
Step 2: Consolidate @Configuration groups (build visibility map)
Step 3: For each module, before generating .module():
        Build full visibility = own defs + includes + @Configuration siblings
        Validate against that set → done, no deferral needed
Step 4: Generate .module() IR (unchanged)
Step 5: startKoin<T>() validates the union of @KoinApplication modules
```

## Implementation Plan

### 1. KoinPluginConstants.kt — Add new hint prefixes

```kotlin
const val COMPONENT_SCAN_HINT_PREFIX = "componentscan_"
const val COMPONENT_SCAN_FUNCTION_HINT_PREFIX = "componentscanfunc_"
```

### 2. KoinModuleFirGenerator.kt (FIR) — Generate module-scoped scan hints

**New data structures:**
```kotlin
data class ModuleScanDefinitionInfo(
    val moduleClassId: ClassId,
    val classSymbol: FirClassSymbol<*>,
    val definitionType: String,
    val containingFileName: String?
)

data class ModuleScanFunctionInfo(
    val moduleClassId: ClassId,
    val functionSymbol: FirNamedFunctionSymbol,
    val definitionType: String,
    val containingFileName: String?,
    val returnTypeClassId: ClassId
)
```

**New companion helpers:**
```kotlin
// Sanitize module FQName for use in function name
// com.example.CoreModule → comExampleCoreModule (same style as syntheticFileName)
fun sanitizeModuleIdForHint(classId: ClassId): String

// Build hint function name: componentscan_comExampleCoreModule_single
fun moduleScanHintFunctionName(moduleId: String, defType: String): Name

// Parse hint function name back to (moduleId, defType)
fun moduleScanInfoFromHintFunctionName(name: String): Pair<String, String>?

// Same for function definitions
fun moduleScanFunctionHintFunctionName(moduleId: String, defType: String): Name
fun moduleScanFunctionInfoFromHintFunctionName(name: String): Pair<String, String>?
```

**New lazy property — collect ALL definitions per module scan:**
```kotlin
private val moduleScanDefinitionInfos: List<ModuleScanDefinitionInfo> by lazy {
    // For each @Configuration module with @ComponentScan:
    //   1. Get its scan packages (from configurationModules + scanClassIds)
    //   2. Find ALL definition classes in those packages (from ALL predicate-discovered defs, NOT just orphans)
    //   3. Create ModuleScanDefinitionInfo per (module, definition) pair
}
```

Key difference from `definitionClassInfos`: this does NOT filter by `isCoveredByLocalScan`. It collects ALL definitions in a module's scan packages, regardless of coverage.

**Modify `getTopLevelCallableIds()`:**
- Add callable IDs for module-scan hints (one per module×defType combo)
- Guard with `isKlibTarget` as before

**Modify `generateFunctions()`:**
- Add new dispatch branch for `componentscan_*` prefix
- Parse module ID and def type from function name
- Generate one hint function per scanned definition, with parameter type = definition class

**Modify `hasPackage()`:**
- Add `|| moduleScanDefinitionInfos.isNotEmpty()` to the condition

### 3. KoinHintTransformer.kt (IR) — Recognize new hints

Add `componentscan_*` and `componentscanfunc_*` recognition to `visitSimpleFunction` so hint function bodies get `error("Stub!")` generated.

### 4. KoinAnnotationProcessor.kt (IR) — Query module scan hints

**New method:**
```kotlin
fun discoverModuleScanDefinitions(moduleFqName: String): List<Definition> {
    val sanitizedId = sanitizeModuleIdForHint(moduleFqName)
    val definitions = mutableListOf<Definition>()

    for (defType in ALL_DEFINITION_TYPES) {
        // Query class definition hints
        val className = Name.identifier("${COMPONENT_SCAN_HINT_PREFIX}${sanitizedId}_$defType")
        val hintFunctions = context.referenceFunctions(CallableId(HINTS_PACKAGE, className))
        // Extract parameter type → resolve class → create Definition.ClassDef

        // Query function definition hints
        val funcName = Name.identifier("${COMPONENT_SCAN_FUNCTION_HINT_PREFIX}${sanitizedId}_$defType")
        val funcHintFunctions = context.referenceFunctions(CallableId(HINTS_PACKAGE, funcName))
        // Extract parameter type → resolve class → create Definition.ExternalFunctionDef
    }

    return definitions
}
```

**Modify `collectDefinitionsFromDependencyModule()`:**
- In the `hasComponentScan` branch, call `discoverModuleScanDefinitions(moduleFqName)` to get scanned definitions
- Still call `discoverClassDefinitionsFromHints()` for transitive cross-module definitions (module A scanning from module C)
- Deduplicate by FQName
- Set `isComplete = true` when module class is resolvable (instead of `!hasComponentScan`)

**Restructure `generateModuleExtensions()`:**
```kotlin
fun generateModuleExtensions(moduleFragment: IrModuleFragment) {
    // Step 1: Pre-collect definitions for every module
    val moduleDefinitions = moduleClasses.associateWith { collectAllDefinitions(it) }

    // Step 2: For each module, build visibility + validate + generate
    for ((moduleClass, definitions) in moduleDefinitions) {
        // Log (unchanged)
        ...

        // Build full visibility set
        val allVisibleDefinitions = buildVisibleDefinitions(moduleClass, definitions, moduleDefinitions)

        // Validate
        if (safetyValidator != null && definitions.isNotEmpty()) {
            safetyValidator.validate(moduleName, definitions, allVisibleDefinitions)
        }

        // Generate .module() IR (unchanged)
        ...
    }
}

private fun buildVisibleDefinitions(
    moduleClass: ModuleClass,
    ownDefinitions: List<Definition>,
    allModuleDefinitions: Map<ModuleClass, List<Definition>>
): List<Definition> {
    return buildList {
        addAll(ownDefinitions)

        // A1: Explicit includes
        for (included in moduleClass.includedModules) {
            val includedModule = moduleClasses.find { it.irClass.fqNameWhenAvailable == included.fqNameWhenAvailable }
            if (includedModule != null) {
                addAll(allModuleDefinitions[includedModule] ?: emptyList())
            }
        }

        // A2: @Configuration siblings
        val configLabels = extractConfigurationLabels(moduleClass.irClass)
        if (configLabels.isNotEmpty()) {
            val siblingNames = KoinConfigurationRegistry.getModuleClassNamesForLabels(configLabels)
            for (siblingName in siblingNames) {
                val sibling = moduleClasses.find { it.irClass.fqNameWhenAvailable?.asString() == siblingName }
                if (sibling != null && sibling != moduleClass) {
                    addAll(allModuleDefinitions[sibling] ?: emptyList())
                } else if (sibling == null) {
                    // Cross-Gradle-module: resolve from JAR + module scan hints (always complete now)
                    addAll(collectDefinitionsFromDependencyModule(siblingName).definitions)
                }
            }
        }
    }
}
```

### 5. CompileSafetyValidator.kt (IR) — Simplify

Remove the complex `validateModule()` with deferral logic. Replace with:

```kotlin
class CompileSafetyValidator(private val qualifierExtractor: QualifierExtractor) {
    private val parameterAnalyzer = ParameterAnalyzer(qualifierExtractor)

    // Track validated modules to avoid re-checking at startKoin
    private val validatedModuleFqNames = mutableSetOf<String>()

    fun validate(
        moduleName: String,
        moduleFqName: String?,
        ownDefinitions: List<Definition>,
        allVisibleDefinitions: List<Definition>
    ) {
        val registry = BindingRegistry()
        registry.validateModule(moduleName, allVisibleDefinitions, parameterAnalyzer, qualifierExtractor, ownDefinitions)
        if (moduleFqName != null) {
            validatedModuleFqNames.add(moduleFqName)
        }
    }

    fun validateFullGraph(
        appName: String,
        allDefinitions: List<Definition>,
        definitionsToValidate: List<Definition>
    ) {
        if (definitionsToValidate.isEmpty()) return
        val registry = BindingRegistry()
        registry.validateModule("$appName (startKoin)", allDefinitions, parameterAnalyzer, qualifierExtractor, definitionsToValidate)
    }

    fun isAlreadyValidated(moduleFqName: String): Boolean = moduleFqName in validatedModuleFqNames
}
```

### 6. KoinStartTransformer.kt (IR) — Simplify A3

The A3 validation at `startKoin<T>()` collects all modules from `@KoinApplication` and validates the union. With the new approach:

1. Collect all modules from `@KoinApplication`
2. For each module, get definitions (local or from dependency)
3. Skip definitions from modules already validated (use `isAlreadyValidated`)
4. Validate the remaining against the full union

No more `hasUnresolvableModules` branching.

### 7. AnnotationModels.kt — Keep `isComplete` with narrowed semantics

Keep `DependencyModuleResult.isComplete` but change when it's `false`:
- `true`: module class resolved, definitions collected (including module-scan hints)
- `false`: module class not on classpath at all (can't resolve `ClassId`)

The `hasComponentScan` case now always produces `isComplete = true` because module-scan hints provide the full picture.

## Edge Cases & Risks

### KLIB Targets (Native, JS, Wasm)
- KLIB targets skip ALL hint generation (existing behavior, KT-82395)
- Module-scan hints also skipped on KLIB → cross-module scan visibility still limited
- Registry-based discovery (System properties) continues to work for configuration modules
- `isComplete` preserved for this case

### Multiple modules scanning the same package
- Two `@Configuration` modules scanning `com.example` both generate hints for same definitions
- IR side must deduplicate by FQName (existing pattern)

### Naming collisions
- Module FQName sanitized using capitalization-join (same as `syntheticFileName`)
- `com.example.CoreModule` → `comExampleCoreModule`
- Full package included → no collision between `app.CoreModule` and `lib.CoreModule`

### Cross-Gradle-module transitive scanning
- Module A scans package from Gradle module C → those definitions have orphan hints (from C's compilation)
- Module B resolving A's scan should also pick up C's orphan hints via `discoverClassDefinitionsFromHints`
- Both sources are combined in `collectDefinitionsFromDependencyModule`

### FIR lazy evaluation order
- `moduleScanDefinitionInfos` depends on `moduleClassInfos` + `configurationModules` + predicate-discovered definitions
- No circular dependency — safe evaluation chain

## Files Modified

| File | Change |
|------|--------|
| `KoinPluginConstants.kt` | Add `COMPONENT_SCAN_HINT_PREFIX`, `COMPONENT_SCAN_FUNCTION_HINT_PREFIX` |
| `KoinModuleFirGenerator.kt` | Add module-scan data structures, lazy collection, hint generation, companion helpers |
| `KoinHintTransformer.kt` | Recognize `componentscan_*` hint functions for body generation |
| `KoinAnnotationProcessor.kt` | Add `discoverModuleScanDefinitions()`, restructure `generateModuleExtensions()` |
| `CompileSafetyValidator.kt` | Simplify to `validate()` + `validateFullGraph()`, remove deferral logic |
| `KoinStartTransformer.kt` | Simplify A3 validation |
| `AnnotationModels.kt` | Narrow `isComplete` semantics (keep field) |

## Test Strategy

- Existing box tests (`testData/box/safety/`) cover single-compilation scenarios → should still pass
- Cross-Gradle-module validation requires `test-apps/` integration tests
- Add a test in `sample-feature-module` + `sample-app` where a `@Configuration` sibling provides types via `@ComponentScan`
