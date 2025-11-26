# Compile-Time Dependency Validation

Design document for compile-time dependency graph validation.

## Goal

Detect missing dependencies at compile time instead of runtime. Currently, if you forget to declare a dependency, you get a runtime crash:

```kotlin
class MyService(val repo: Repository)

val module = module {
    single<MyService>()  // Compiles fine, crashes at runtime: "No definition for Repository"
}
```

## Key Insight: Plugin Already Has All The Information

The current plugin transformation **already scans** constructor/function parameters to generate `get()`, `getOrNull()`, `inject()` calls. This means we know exactly:

1. **What is provided** - Each `single<T>()`, `factory<T>()`, etc. provides type `T`
2. **What is required** - Each parameter in the constructor/function

```kotlin
// When transforming single<MyService>(), the plugin sees:
// - Provides: MyService
// - Requires: Repository (non-nullable), Logger? (nullable), Config (has default)
```

## Implementation: Two-Pass Approach

### Pass 1: Collect bindings (during IR transformation)

```kotlin
// In KoinDSLTransformer
data class Binding(
    val providedType: IrType,
    val qualifier: String?,
    val scope: String?,  // "_root_" or scope name
    val requirements: List<Requirement>
)

data class Requirement(
    val type: IrType,
    val qualifier: String?,
    val isNullable: Boolean,
    val hasDefault: Boolean,
    val isInjectedParam: Boolean,
    val isLazy: Boolean
)

// Collect while transforming
private val bindings = mutableListOf<Binding>()

override fun visitCall(expression: IrCall): IrExpression {
    // ... existing transformation logic ...

    // After transforming, record the binding
    bindings.add(Binding(
        providedType = returnTypeClass.defaultType,
        qualifier = getNamedAnnotationValue(returnTypeClass),
        scope = currentScope,
        requirements = referencedFunction.valueParameters.map { param ->
            Requirement(
                type = param.type,
                qualifier = getNamedAnnotationValueFromParameter(param),
                isNullable = param.type.isNullable(),
                hasDefault = param.hasDefaultValue(),
                isInjectedParam = hasInjectedParamAnnotation(param),
                isLazy = isLazyType(param.type)
            )
        }
    ))

    return transformedCall
}
```

### Pass 2: Validate graph (at end of module processing)

```kotlin
// In SimpleIrGenerationExtension
override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    // Pass 1: Transform and collect
    moduleFragment.transform(KoinDSLTransformer(pluginContext), null)

    // Pass 2: Validate
    validateDependencyGraph(pluginContext)
}

private fun validateDependencyGraph(context: IrPluginContext) {
    val provided = bindings.map { TypeKey(it.providedType, it.qualifier, it.scope) }.toSet()

    for (binding in bindings) {
        for (req in binding.requirements) {
            // Skip validation for these cases
            if (req.isInjectedParam) continue  // Provided at runtime via parametersOf()
            if (req.isNullable) continue       // getOrNull() handles missing
            if (req.hasDefault) continue       // Default value used if missing

            val key = TypeKey(req.type.unwrapLazy(), req.qualifier, binding.scope)
            if (key !in provided) {
                // Report error
                context.reportCompilationError(
                    "Missing dependency: ${req.type.render()}" +
                    (req.qualifier?.let { " with qualifier '$it'" } ?: "") +
                    " required by ${binding.providedType.render()}"
                )
            }
        }
    }
}
```

## What We Can Validate

| Scenario | Validation |
|----------|------------|
| Non-nullable param, no definition | **ERROR** |
| Nullable param, no definition | OK (uses `getOrNull()`) |
| Param with default, no definition | OK (uses default) |
| `@InjectedParam`, no definition | OK (provided via `parametersOf()`) |
| `Lazy<T>`, no definition for T | **ERROR** |
| `@Named("x")` param, no matching qualifier | **ERROR** |
| Scoped dependency from wrong scope | **ERROR** |

## Reporting Errors

Use IR diagnostics or FIR diagnostics:

```kotlin
// Option 1: IR phase - throw exception (stops compilation)
throw IrCompilationException("Missing dependency: Repository required by MyService")

// Option 2: FIR phase - proper diagnostic (better IDE integration)
reporter.reportOn(source, KoinDiagnostics.MISSING_DEPENDENCY, "Repository", "MyService")
```

## Cross-Module Validation

For multi-module projects, combine with hint generation:

```kotlin
// Module A generates hint for what it provides
// org.koin.plugin.hints package
fun koinProvides_ComExampleModuleA(t1: ServiceA, t2: ServiceB) = error("Stub!")

// Module B generates hint for what it requires
fun koinRequires_ComExampleModuleB(t1: Repository, t2: ServiceA) = error("Stub!")

// App module queries all hints and validates complete graph
```

## Challenges

1. **Generics** - `List<String>` vs `List<Int>` need type argument comparison
2. **Qualifiers** - Must match exactly (`@Named("prod")` != `@Named("test")`)
3. **Scopes** - Root scope can see all, named scopes are isolated
4. **Dynamic modules** - Modules added at runtime can't be validated
5. **Third-party modules** - External Koin modules (no source) can't be scanned

## Why This Is Easier Than Dagger

Dagger needs complex graph resolution because:
- Bindings can come from `@Provides`, `@Binds`, `@Inject` constructors
- Multibindings (`@IntoSet`, `@IntoMap`)
- Component dependencies and subcomponents

With Koin's plugin approach:
- Every definition is explicit: `single<T>()`, `factory<T>()`
- Requirements are just constructor parameters
- No implicit bindings to track
- **The transformation already does the hard work!**