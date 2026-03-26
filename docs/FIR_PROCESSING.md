# FIR Processing Deep Dive

This document captures all learnings about FIR (Frontend Intermediate Representation) processing in Kotlin compiler plugins, including mechanisms, constraints, and solutions discovered during Koin plugin development.

## Table of Contents

1. [FIR vs IR: Fundamental Differences](#1-fir-vs-ir-fundamental-differences)
2. [FIR Extension API](#2-fir-extension-api)
3. [Source Element Types](#3-source-element-types)
4. [KMP Multi-Phase Compilation](#4-kmp-multi-phase-compilation)
5. [Synthetic File Generation](#5-synthetic-file-generation)
6. [Cross-Module Discovery](#6-cross-module-discovery)
7. [FIR to IR Communication](#7-fir-to-ir-communication)
8. [Common Pitfalls and Solutions](#8-common-pitfalls-and-solutions)

---

## 1. FIR vs IR: Fundamental Differences

### What Each Phase Can Do

| Capability | FIR Phase | IR Phase |
|------------|-----------|----------|
| Create new declarations (classes, functions, properties) | ✅ | ❌ |
| Add function/property bodies | ❌ | ✅ |
| Transform existing code | ❌ | ✅ |
| Access classes from dependencies (JARs) | ✅ (via symbolProvider) | ✅ (via referenceClass) |
| Generate metadata-visible symbols | ✅ | ✅ (via metadataDeclarationRegistrar) |

### Why Both Are Needed

```
FIR Phase: "I declare that MyModule.module() exists"
    ↓
IR Phase: "I fill the body: module { buildSingle(...) }"
```

FIR cannot add bodies because it runs before type resolution is complete. IR cannot create new top-level declarations because the symbol table is already finalized.

---

## 2. FIR Extension API

### Entry Point: FirDeclarationGenerationExtension

```kotlin
@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
class MyFirGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {

    // 1. Register predicates to find annotated classes
    override fun FirDeclarationPredicateRegistrar.registerPredicates() {
        register(LookupPredicate.create { annotated(MY_ANNOTATION) })
    }

    // 2. Declare what callable IDs we will generate
    override fun getTopLevelCallableIds(): Set<CallableId> {
        return setOf(CallableId(packageName, functionName))
    }

    // 3. Generate function symbols (no bodies yet!)
    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?
    ): List<FirNamedFunctionSymbol> {
        return listOf(createTopLevelFunction(Key, callableId, returnType) {
            extensionReceiverType(receiverType)
            valueParameter(paramName, paramType)
        }.symbol)
    }

    // 4. Claim ownership of packages for generated symbols
    override fun hasPackage(packageFqName: FqName): Boolean {
        return packageFqName == myGeneratedPackage || super.hasPackage(packageFqName)
    }

    object Key : GeneratedDeclarationKey()
}
```

### Key Methods

| Method | Purpose | When Called |
|--------|---------|-------------|
| `registerPredicates()` | Register annotation lookups | Early, before analysis |
| `getTopLevelCallableIds()` | Declare generated symbols | During symbol collection |
| `generateFunctions()` | Create function symbols | When symbols are resolved |
| `generateProperties()` | Create property symbols | When symbols are resolved |
| `hasPackage()` | Claim package ownership | During package resolution |

### Critical Constraints

1. **No symbolProvider in getTopLevelCallableIds()**: Causes infinite recursion
2. **No bodies in FIR**: Only declarations, bodies are filled in IR
3. **Lazy initialization**: Use `by lazy {}` for caches that depend on predicates

---

## 3. Source Element Types

FIR uses different source element types depending on how the class was loaded:

### KtPsiSourceElement

**When**: Direct source files in the current compilation unit (standard JVM/JS builds)

```kotlin
when (source) {
    is KtPsiSourceElement -> {
        // Direct PSI access available
        val psi = source.psi
        val file = psi?.containingFile
        val fileName = file?.name  // e.g., "MyModule.kt"
    }
}
```

### KtLightSourceElement with RealSourceElementKind

**When**: KMP source files (no direct PSI access due to metadata-based analysis)

```kotlin
val sourceKind = source?.kind
val isRealSource = sourceKind?.toString()?.contains("RealSourceElementKind") == true
if (isRealSource) {
    // This is a source file, but we can't get filename from PSI
    // Need to use synthetic file name
}
```

### Null/Metadata Source

**When**: Classes from compiled dependencies (JARs, klibs)

```kotlin
if (source == null || !isRealSource) {
    // Skip - this class is from a dependency
    // Its module() function is already compiled in the JAR
}
```

### Decision Tree

```
source?.kind
    │
    ├── is KtPsiSourceElement
    │   └── Get filename from PSI: source.psi?.containingFile?.name
    │
    ├── is KtLightSourceElement with RealSourceElementKind
    │   └── Source file, use synthetic filename (KMP)
    │
    └── null or other
        └── Skip (dependency class from JAR)
```

---

## 4. KMP Multi-Phase Compilation

### The Problem

In KMP projects, FIR runs in **separate phases** for each source set:

```
Phase 1: commonMain
├── Sees: expect classes, common classes
├── Generates: module() for common classes
└── Output: Synthetic files (or metadata)

Phase 2: androidMain (or iosMain, etc.)
├── Sees: actual classes + commonMain metadata
├── Generates: module() for actual classes
└── Output: Platform-specific synthetic files
```

### Expect/Actual Handling

**Problem**: Generating `module()` for expect classes causes duplicate definitions.

**Solution**: Skip expect classes, only generate for actual classes:

```kotlin
private val moduleClasses: List<FirClassSymbol<*>> by lazy {
    session.predicateBasedProvider.getSymbolsByPredicate(modulePredicate)
        .filterIsInstance<FirClassSymbol<*>>()
        .filter { classSymbol ->
            // Skip expect classes - only actual classes should have module()
            val isExpect = classSymbol.rawStatus.isExpect
            if (isExpect) {
                log("  Skipping expect class: ${classSymbol.classId}")
            }
            !isExpect
        }
}
```

### Source Set Detection

```kotlin
// Detect if we're compiling for K/Native
val isNativeTarget = session.moduleData.platform.isNative()

// Detect if class is actual (vs expect)
val isActual = classSymbol.rawStatus.isActual
```

---

## 5. Synthetic File Generation

### The containingFileName Parameter

Kotlin 2.3.0+ added `containingFileName` parameter to `createTopLevelFunction`:

```kotlin
createTopLevelFunction(
    Key,
    callableId,
    returnType,
    containingFileName = "MyFile.kt"  // Where to place the function
) {
    extensionReceiverType(type)
}
```

### File Name Strategies

#### Strategy 1: Use Source File (When Available)

```kotlin
val containingFile = when (source) {
    is KtPsiSourceElement -> source.psi?.containingFile?.name
    else -> null
}
```

**Pro**: Functions appear in the same file as their class
**Con**: Not available for KtLightSourceElement (KMP)

#### Strategy 2: Deterministic Synthetic Names (Recommended)

Deterministic naming based on class ID:

```kotlin
fun syntheticFileName(classId: ClassId, suffix: String): String {
    val parts = sequence {
        yieldAll(classId.packageFqName.pathSegments().map { it.asString() })
        yield(classId.shortClassName.asString())
        yield(suffix)
    }
    val fileName = parts
        .map { segment -> segment.replaceFirstChar { it.uppercaseChar() } }
        .joinToString(separator = "")
        .replaceFirstChar { it.lowercaseChar() }
    return "$fileName.kt"
}

// Examples:
// "com.example.DataModule" + "Module" -> "comExampleDataModuleModule.kt"
// "feature.FeatureModule" + "Configuration" -> "featureFeatureModuleConfiguration.kt"
```

**Pro**: Deterministic, works on all platforms including K/Native
**Pro**: Unique per class, no overwrites between phases
**Con**: Long file names for deep packages

#### Strategy 3: Unique Per-Class Names (Legacy)

```kotlin
val className = classSymbol.classId.shortClassName.asString()
val packageName = classSymbol.classId.packageFqName.asString().replace(".", "_")
val effectiveFileName = "__Module_${packageName}_${className}__.kt"
```

**Pro**: Unique per class
**Con**: Non-deterministic format, verbose

### K/Native Considerations

Kotlin/Native has specific constraints around FIR-generated synthetic files that affect the plugin.

#### Problem 1: Source File Count Mismatch (Kotlin < 2.3.20)

Synthetic files cause "source file count mismatch" errors during K/Native compilation.

#### Problem 2: ObjC Export Failure (All K/Native versions)

When K/Native generates Objective-C headers for iOS frameworks, it fails to find source files for FIR-generated declarations:

```
e: kotlin.NotImplementedError: An operation is not implemented.
    at org.jetbrains.kotlin.backend.common.serialization.LegacyDescriptorUtilsKt.findSourceFile
    at org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportHeaderGenerator.translatePackageFragments
```

This error originates from the **Kotlin compiler itself** (not our plugin), but is triggered by our FIR-generated declarations that use synthetic file names.

#### Solution: Skip Synthetic Generation on Native Targets

The fix is to detect K/Native and skip function generation when no real source file is available:

```kotlin
// Check if we're compiling for a Kotlin/Native target
private val isNativeTarget: Boolean by lazy {
    session.moduleData.platform.isNative()
}

// In generateFunctions()
if (containingFile == null && isNativeTarget) {
    log("Skipping function on Native target (no source file)")
    return@mapNotNull null
}
```

#### What Gets Skipped on Native

| Declaration Type | Behavior on Native |
|------------------|-------------------|
| `module()` extension with real source file | Generated normally |
| `module()` extension with synthetic file | Skipped |
| Hint functions (always synthetic) | Skipped |

#### Impact on Cross-Module Discovery

Hint functions are skipped on Native, but this is acceptable because:
1. Cross-module discovery happens via the registry populated during earlier compilation phases
2. Native targets typically consume modules from JVM/common compilations where hints are generated
3. The `module()` functions from dependencies are already in klibs

#### Source File Detection in KMP

In KMP, source files are detected differently than JVM:

```kotlin
val containingFile = when (source) {
    is KtPsiSourceElement -> {
        // JVM/JS: Direct PSI access
        source.psi?.containingFile?.name
    }
    else -> {
        // KMP: Check if it's a real source (not metadata)
        val isRealSource = source?.kind?.toString()?.contains("RealSourceElementKind") == true
        if (isRealSource) {
            // Use synthetic file name (will be skipped on Native)
            syntheticFileName(classId, "Module")
        } else {
            // Dependency from JAR - skip
            null
        }
    }
}
```

---

## 6. Cross-Module Discovery

### The Problem

IR phase cannot iterate classes from dependencies (JARs). How do we discover `@Configuration` modules from other modules?

### Solution: Hint Functions

Generate marker functions that encode module metadata:

```kotlin
// Generated in hints package
package org.koin.plugin.hints

fun configuration_default(contributed: MyModule): Unit = error("Stub!")
fun configuration_test(contributed: TestModule): Unit = error("Stub!")
```

### FIR Phase: Generating Hints

```kotlin
override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?
): List<FirNamedFunctionSymbol> {
    if (callableId.packageName == HINTS_PACKAGE) {
        val label = labelFromHintFunctionName(callableId.callableName.asString())
        val modulesWithLabel = configurationModules.filter { it.labels.contains(label) }

        return modulesWithLabel.map { configModule ->
            createTopLevelFunction(
                Key,
                callableId,
                session.builtinTypes.unitType.coneType,
                containingFileName = syntheticFileName(configModule.classSymbol.classId, "Configuration")
            ) {
                valueParameter(Name.identifier("contributed"), moduleType)
            }.symbol
        }
    }
}
```

### FIR Phase: Discovering from JARs

```kotlin
private fun discoverModulesFromHintsIfNeeded() {
    for (label in labelsToQuery) {
        val functionName = hintFunctionNameForLabel(label)

        // Query symbolProvider for hint functions in dependencies
        val hintFunctions = session.symbolProvider.getTopLevelFunctionSymbols(
            HINTS_PACKAGE,    // FqName("org.koin.plugin.hints")
            functionName      // Name("configuration_default")
        )

        // Extract module class from parameter type
        for (hintFunc in hintFunctions) {
            val paramType = hintFunc.fir.valueParameters.firstOrNull()?.returnTypeRef?.coneType
            val moduleClassId = paramType?.classId
            if (moduleClassId != null) {
                KoinConfigurationRegistry.registerJarModule(
                    moduleClassId.asSingleFqName().asString(),
                    label
                )
            }
        }
    }
}
```

### IR Phase: Registering for Metadata Visibility

**Critical**: Without this, hints won't be visible to downstream modules:

```kotlin
// In IR transformer
context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(declaration)
```

### Package Ownership

FIR must claim ownership of the hints package:

```kotlin
override fun hasPackage(packageFqName: FqName): Boolean {
    if (packageFqName == HINTS_PACKAGE && configurationModules.isNotEmpty()) {
        return true
    }
    return super.hasPackage(packageFqName)
}
```

Without this, KMP builds fail with "Module doesn't contain package" errors.

---

## 7. FIR to IR Communication

### The Challenge

FIR and IR phases run in **different classloaders**. Static registries don't work:

```kotlin
// DOESN'T WORK
object Registry {
    val modules = mutableSetOf<String>()  // Different instance in each classloader!
}
```

### Solution: System Properties

```kotlin
object KoinConfigurationRegistry {
    private const val MODULES_PROPERTY = "koin.plugin.configuration.modules"

    // FIR writes
    fun registerModule(moduleClassName: String, labels: List<String>) {
        synchronized(System.getProperties()) {
            val labelMap = parseProperty()
            for (label in labels) {
                labelMap.getOrPut(label) { mutableSetOf() }.add(moduleClassName)
            }
            System.setProperty(MODULES_PROPERTY, serializeProperty(labelMap))
        }
    }

    // IR reads
    fun getModuleClassNamesForLabels(labels: List<String>): Set<String> {
        val labelMap = parseProperty()
        return labels.flatMap { labelMap[it] ?: emptySet() }.toSet()
    }

    // Serialization: "label1:mod1,mod2;label2:mod1,mod3"
    private fun serializeProperty(labelMap: Map<String, Set<String>>): String {
        return labelMap.entries.joinToString(";") { (label, modules) ->
            "$label:${modules.joinToString(",")}"
        }
    }
}
```

### Why System Properties?

- Survive classloader boundaries
- Thread-safe with synchronization
- Simple key-value storage
- JVM-global (single compilation process)

---

## 8. Common Pitfalls and Solutions

### Pitfall 1: Infinite Recursion in getTopLevelCallableIds()

**Problem**:
```kotlin
override fun getTopLevelCallableIds(): Set<CallableId> {
    // BAD: symbolProvider calls getTopLevelCallableIds() internally
    val functions = session.symbolProvider.getTopLevelFunctionSymbols(...)
}
```

**Solution**: Move symbolProvider queries to `generateFunctions()` or use lazy initialization:

```kotlin
override fun generateFunctions(...): List<FirNamedFunctionSymbol> {
    discoverModulesFromHintsIfNeeded()  // Safe here
    // ...
}
```

### Pitfall 2: Source File Info Lost Between FIR Calls

**Problem**: Source type changes between `registerPredicates()` and `generateFunctions()` in KMP.

**Solution**: Capture source file info during initial discovery:

```kotlin
private data class ModuleClassInfo(
    val classSymbol: FirClassSymbol<*>,
    val containingFileName: String?  // Captured at discovery time
)

private val moduleClassInfos: List<ModuleClassInfo> by lazy {
    session.predicateBasedProvider.getSymbolsByPredicate(modulePredicate)
        .mapNotNull { classSymbol ->
            val fileName = extractFileName(classSymbol)  // Capture NOW
            ModuleClassInfo(classSymbol, fileName)
        }
}
```

### Pitfall 3: KMP Phase Overwrites

**Problem**: Both commonMain and androidMain phases generate to `__GENERATED__.kt`, causing overwrites.

**Solution**: Use unique file names per class (see [Synthetic File Generation](#5-synthetic-file-generation)).

### Pitfall 4: Missing hasPackage() Override

**Problem**: KMP builds fail with "Module doesn't contain package org.koin.plugin.hints".

**Solution**: Override `hasPackage()`:

```kotlin
override fun hasPackage(packageFqName: FqName): Boolean {
    if (packageFqName == HINTS_PACKAGE) return true
    return super.hasPackage(packageFqName)
}
```

### Pitfall 5: Hints Not Visible to Downstream Modules

**Problem**: Hint functions generated in module A are not visible in module B.

**Solution**: Register as metadata-visible in IR phase:

```kotlin
context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(declaration)
```

### Pitfall 6: Different Source Types in KMP

**Problem**: JVM uses `KtPsiSourceElement`, KMP uses `KtLightSourceElement`.

**Solution**: Handle both:

```kotlin
val containingFile = when (source) {
    is KtPsiSourceElement -> source.psi?.containingFile?.name
    else -> {
        val isRealSource = source?.kind?.toString()?.contains("RealSourceElementKind") == true
        if (isRealSource) syntheticFileName(classId, "Module") else null
    }
}
```

### Pitfall 7: Expect Class Duplication

**Problem**: `module()` generated for both expect and actual classes.

**Solution**: Filter out expect classes:

```kotlin
.filter { !classSymbol.rawStatus.isExpect }
```

### Pitfall 8: K/Native ObjC Export Crash

**Problem**: iOS/macOS framework linking fails with `NotImplementedError: An operation is not implemented` in `findSourceFile`.

**Cause**: K/Native's ObjC export phase tries to find source files for FIR-generated declarations that use synthetic file names.

**Solution**: Skip function generation on Native targets when no real source file is available:

```kotlin
private val isNativeTarget: Boolean by lazy {
    session.moduleData.platform.isNative()
}

// In generateFunctions()
val containingFile = when (source) {
    is KtPsiSourceElement -> source.psi?.containingFile?.name
    else -> null
}

if (containingFile == null && isNativeTarget) {
    return@mapNotNull null  // Skip synthetic generation on Native
}
```

**Note**: This error comes from the Kotlin compiler, not our plugin code, but is triggered by our synthetic declarations.

---

## Quick Reference

### FIR Extension Lifecycle

```
1. registerPredicates()     - Register what to look for
2. getTopLevelCallableIds() - Declare what will be generated
3. generateFunctions()      - Create function symbols (no bodies)
4. generateProperties()     - Create property symbols (no bodies)
```

### Source Type Cheat Sheet

| Source Type | Platform | PSI Access | Action |
|-------------|----------|------------|--------|
| `KtPsiSourceElement` | JVM/JS | Yes | Use `source.psi?.containingFile?.name` |
| `KtLightSourceElement` + RealSourceElementKind | KMP | No | Use synthetic file name |
| `null` / other | Dependency | No | Skip (already compiled) |

### Key APIs

```kotlin
// Find classes by predicate
session.predicateBasedProvider.getSymbolsByPredicate(predicate)

// Query functions from dependencies
session.symbolProvider.getTopLevelFunctionSymbols(packageName, functionName)

// Create function symbol
createTopLevelFunction(key, callableId, returnType, containingFileName) { ... }

// Check platform
session.moduleData.platform.isNative()

// Check expect/actual
classSymbol.rawStatus.isExpect
classSymbol.rawStatus.isActual
```

### Kotlin Version Notes

| Version | Feature |
|---------|---------|
| 2.3.0+ | `containingFileName` parameter in `createTopLevelFunction` |
| 2.3.20 | ObjC export still fails with synthetic files (requires skip on Native) |
| All K2 | FIR + IR phases required |

### Platform-Specific Behavior

| Platform | PSI Source | Synthetic Files | ObjC Export |
|----------|------------|-----------------|-------------|
| JVM | `KtPsiSourceElement` | Works | N/A |
| JS | `KtPsiSourceElement` | Works | N/A |
| iOS/macOS | `KtLightSourceElement` | Skip required | Fails without skip |
| watchOS/tvOS | `KtLightSourceElement` | Skip required | Fails without skip |
| Linux/Windows | `KtLightSourceElement` | Skip required | N/A |
