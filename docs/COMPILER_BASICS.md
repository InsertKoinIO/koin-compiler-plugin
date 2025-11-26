# Kotlin Compiler Plugin Basics

Fundamentals of Kotlin compiler plugin development. This guide covers concepts applicable to any compiler plugin, not just Koin.

## Table of Contents

1. [KSP vs Compiler Plugins](#1-ksp-vs-compiler-plugins)
2. [Compilation Pipeline](#2-compilation-pipeline)
3. [IR Fundamentals](#3-ir-fundamentals)
4. [Visitor Pattern](#4-visitor-pattern)
5. [Creating IR Elements](#5-creating-ir-elements)
6. [Common Patterns](#6-common-patterns)
7. [Debugging Techniques](#7-debugging-techniques)

---

## 1. KSP vs Compiler Plugins

### KSP (Kotlin Symbol Processing)
- **What**: Code generation based on annotations/symbols
- **Use when**: Generate boilerplate, create new files
- **Limitations**: Cannot modify existing code, no access to method bodies

### Compiler Plugins
- **What**: Full access to compilation, can transform code
- **Use when**: Modify behavior, inject code, transform IR
- **Power**: Can do anything the compiler can do

**Rule**: Use KSP for code generation, Compiler Plugins for code transformation.

---

## 2. Compilation Pipeline

```
Source Code (.kt)
    ↓
[Frontend] → PSI (Program Structure Interface)
    ↓
[FIR] → Frontend Intermediate Representation (K2 compiler)
    ↓
[Backend] → IR (Intermediate Representation)
    ↓
[Codegen] → JVM Bytecode / JS / Native
```

### Extension Points

| Phase | Extension | Purpose |
|-------|-----------|---------|
| FIR | `FirExtensionRegistrar` | Generate declarations, modify types |
| IR | `IrGenerationExtension` | Transform code, inject bodies |
| CLI | `CompilerPluginRegistrar` | Configure plugin options |

---

## 3. IR Fundamentals

### IR Tree Structure

```kotlin
// Kotlin source
fun hello(): String = "Hello"

// Becomes IR tree
IrSimpleFunction(
    name = "hello",
    returnType = String,
    body = IrBlockBody(
        statements = [
            IrReturn(
                value = IrConst(type = String, value = "Hello")
            )
        ]
    )
)
```

### Key IR Classes

| Class | Purpose |
|-------|---------|
| `IrElement` | Base class for all IR nodes |
| `IrDeclaration` | Classes, functions, properties |
| `IrFunction` / `IrSimpleFunction` | Function declarations |
| `IrClass` | Class declarations |
| `IrProperty` | Properties |
| `IrExpression` | Expressions (calls, constants, etc.) |
| `IrStatement` | Statements (returns, loops, etc.) |
| `IrCall` | Function/method calls |

### IrPluginContext

Your gateway to compiler internals:

```kotlin
class MyExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val irBuiltIns = pluginContext.irBuiltIns  // Built-in types
        val irFactory = pluginContext.irFactory     // Create IR elements

        // Find classes by FQN
        val myClass = pluginContext.referenceClass(
            ClassId(FqName("com.example"), Name.identifier("MyClass"))
        )

        // Find functions
        val printlnFunction = pluginContext.referenceFunctions(
            CallableId(FqName("kotlin.io"), Name.identifier("println"))
        ).first()
    }
}
```

---

## 4. Visitor Pattern

### Transformer (Modify)

```kotlin
class MyTransformer(private val context: IrPluginContext) : IrElementTransformerVoid() {

    override fun visitCall(expression: IrCall): IrExpression {
        val call = super.visitCall(expression) as IrCall

        // Check if this is the call we want to transform
        if (call.symbol.owner.name == Name.identifier("myFunction")) {
            // Return transformed expression
            return createReplacementCall(call)
        }

        return call
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        // Modify function
        return super.visitFunction(declaration)
    }
}
```

### Visitor (Read-Only)

```kotlin
class MyVisitor : IrElementVisitorVoid() {

    override fun visitFunction(declaration: IrFunction) {
        println("Found function: ${declaration.name}")
        super.visitFunction(declaration)
    }

    override fun visitClass(declaration: IrClass) {
        println("Found class: ${declaration.name}")
        super.visitClass(declaration)
    }
}
```

### Usage

```kotlin
override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    // Transform
    moduleFragment.transform(MyTransformer(pluginContext), null)

    // Visit (read-only)
    moduleFragment.acceptChildrenVoid(MyVisitor())
}
```

---

## 5. Creating IR Elements

### Constants

```kotlin
// String constant
IrConstImpl.string(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.stringType, "Hello")

// Int constant
IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.intType, 42)

// Boolean constant
IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.booleanType, true)

// Null constant
IrConstImpl.constNull(UNDEFINED_OFFSET, UNDEFINED_OFFSET, nullableType)
```

### Function Calls

```kotlin
val builder = DeclarationIrBuilder(context, currentFunctionSymbol)

// Simple call
builder.irCall(functionSymbol).apply {
    putTypeArgument(0, typeArg)
    putValueArgument(0, valueArg)
}

// Constructor call
builder.irCallConstructor(constructorSymbol, emptyList()).apply {
    putValueArgument(0, arg1)
    putValueArgument(1, arg2)
}
```

### Return Statement

```kotlin
builder.irReturn(expression)
```

### Block Body

```kotlin
val body = context.irFactory.createBlockBody(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    listOf(statement1, statement2, returnStatement)
)
function.body = body
```

### Lambda Expression

```kotlin
// 1. Create lambda symbol
val lambdaSymbol = IrSimpleFunctionSymbolImpl()

// 2. Create lambda function
val lambdaFunction = context.irFactory.createSimpleFunction(
    startOffset = UNDEFINED_OFFSET,
    endOffset = UNDEFINED_OFFSET,
    origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
    name = Name.special("<anonymous>"),
    visibility = DescriptorVisibilities.LOCAL,
    isInline = false,
    isExpect = false,
    returnType = irBuiltIns.stringType,
    modality = Modality.FINAL,
    symbol = lambdaSymbol,
    isTailrec = false,
    isSuspend = false,
    isOperator = false,
    isInfix = false,
    isExternal = false,
    isFakeOverride = false
).apply {
    parent = builder.scope.getLocalDeclarationParent()

    // Create body with scoped builder
    val lambdaBuilder = DeclarationIrBuilder(context, lambdaSymbol)
    body = context.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET).apply {
        statements.add(lambdaBuilder.irReturn(IrConstImpl.string(..., "hello")))
    }
}

// 3. Wrap in expression
val lambda = IrFunctionExpressionImpl(
    startOffset = UNDEFINED_OFFSET,
    endOffset = UNDEFINED_OFFSET,
    type = irBuiltIns.functionN(0).typeWith(irBuiltIns.stringType),
    function = lambdaFunction,
    origin = IrStatementOrigin.LAMBDA
)
```

**Critical**: Use `lambdaBuilder` (scoped to lambda) for `irReturn`, not the outer builder!

---

## 6. Common Patterns

### Finding Symbols

```kotlin
// Find a class
val classSymbol = context.referenceClass(
    ClassId(FqName("com.example"), Name.identifier("MyClass"))
)

// Find functions (returns multiple overloads)
val functions = context.referenceFunctions(
    CallableId(FqName("kotlin.io"), Name.identifier("println"))
)

// Find constructors
val constructors = context.referenceConstructors(classId)

// Find properties
val properties = context.referenceProperties(
    CallableId(FqName("com.example"), Name.identifier("myProperty"))
)
```

### Type Handling

```kotlin
// Built-in types
context.irBuiltIns.stringType
context.irBuiltIns.intType
context.irBuiltIns.unitType
context.irBuiltIns.nothingType
context.irBuiltIns.anyType

// Make nullable
val nullableString = context.irBuiltIns.stringType.makeNullable()

// Check types
param.type.isNullable()
param.type.isString()
param.type.isMarkedNullable()

// Generic types
val lazyClass = context.referenceClass(ClassId(FqName("kotlin"), Name.identifier("Lazy")))
val lazyOfString = lazyClass!!.typeWith(irBuiltIns.stringType)
```

### Checking Annotations

```kotlin
fun IrClass.hasAnnotation(fqName: FqName): Boolean {
    return annotations.any { annotation ->
        annotation.type.classFqName == fqName
    }
}

// Get annotation argument
val annotation = declaration.annotations.first { it.type.classFqName == myAnnotationFqn }
val nameArg = annotation.getValueArgument(0)  // First argument
```

### Always Use Builders

```kotlin
// CORRECT
val call = builder.irCall(someSymbol)
val body = context.irFactory.createBlockBody(start, end, statements)

// WRONG - Don't use constructors directly
val call = IrCallImpl(...)  // Fragile, deprecated
```

### Offset Management

Use `UNDEFINED_OFFSET` (-1) for generated code:

```kotlin
const val UNDEFINED_OFFSET = -1

val element = IrConstImpl(
    startOffset = UNDEFINED_OFFSET,
    endOffset = UNDEFINED_OFFSET,
    // ...
)
```

---

## 7. Debugging Techniques

### Dump IR

```kotlin
// Full IR tree
println(declaration.dump())

// Kotlin-like representation
println(declaration.dumpKotlinLike())

// Type as string
println(type.render())
```

### Print Symbols

```kotlin
println("Symbol: ${symbol.owner.fqNameWhenAvailable}")
println("Signature: ${declaration.symbol.signature}")
```

### Trace Execution

```kotlin
override fun visitCall(expression: IrCall): IrExpression {
    println("DEBUG: Processing call ${expression.symbol.owner.fqNameWhenAvailable}")
    println("DEBUG: Args count = ${expression.valueArgumentsCount}")
    println("DEBUG: Type args = ${expression.typeArgumentsCount}")
    return super.visitCall(expression)
}
```

### Verify Parent Chain

```kotlin
fun printParentChain(node: IrElement) {
    var current: IrDeclarationParent? = (node as? IrDeclaration)?.parent
    while (current != null) {
        println("Parent: ${current.javaClass.simpleName}")
        current = (current as? IrDeclaration)?.parent
    }
}
```

### Save IR to File

```kotlin
import java.io.File

override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    File("/tmp/ir-before.txt").writeText(moduleFragment.dump())

    // ... transformations ...

    File("/tmp/ir-after.txt").writeText(moduleFragment.dump())
}
```

Then diff: `diff /tmp/ir-before.txt /tmp/ir-after.txt`

---

## Common Errors & Solutions

### Non-Local Return

```
java.lang.NoClassDefFoundError: $$$$$NON_LOCAL_RETURN$$$$$
```

**Cause**: Using wrong builder for lambda return

**Fix**: Use builder scoped to lambda symbol:
```kotlin
val lambdaBuilder = DeclarationIrBuilder(context, lambdaSymbol)
lambdaBuilder.irReturn(value)  // NOT outerBuilder.irReturn!
```

### Missing Parent

**Cause**: IR node without parent

**Fix**: Always set parent:
```kotlin
lambdaFunction.parent = builder.scope.getLocalDeclarationParent()
```

### Deprecated API

```
This compiler API is deprecated and will be removed soon.
```

**Fix**: In build.gradle.kts:
```kotlin
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(false)
    }
}
```

### Unresolved Reference

```
Unresolved reference 'IrCallImpl'
```

**Fix**: Use builder instead:
```kotlin
builder.irCall(symbol)  // Not IrCallImpl(...)
```

---

## Resources

### Official
- [Kotlin Compiler Plugin Guide](https://kotlinlang.org/docs/compiler-plugins.html)
- [K2 Compiler Architecture](https://youtrack.jetbrains.com/issue/KT-42286)
- [Kotlin Compiler Test Framework](https://github.com/JetBrains/kotlin/blob/master/compiler/test-infrastructure/ReadMe.md)

### Example Projects
- [Jetpack Compose Compiler](https://github.com/androidx/androidx/tree/androidx-main/compose/compiler) - Most advanced
- [Kotlin Power-Assert](https://github.com/bnorm/kotlin-power-assert) - Enhances asserts
- [Metro DI](https://github.com/ZacSweers/metro) - DI with multi-version compat
- [Arrow Meta](https://github.com/arrow-kt/arrow-meta) - Functional programming

### Tools
- [Kotlin Compiler DevKit](https://github.com/JetBrains/kotlin-compiler-devkit) - IntelliJ plugin for testing
