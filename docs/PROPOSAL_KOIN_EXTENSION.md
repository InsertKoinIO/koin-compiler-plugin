# Proposal — `@KoinExtension` annotation for compile-safety-visible helper functions

> **Status**: design — targeted for 1.0.1
> **Related**: the original "inline reified `Module` extension invisible to compileSafety" issue this proposal solves

## Why this exists

Today's compile-safety walker (as of 1.0.0) sees Koin's safe DSL (`single<T>()`, `factory<T>()`, etc.) and `single { create(::T) }` bridge form, but doesn't see classic-DSL provider calls written directly inside a `module { … }` lambda, nor helper extension functions that wrap such calls:

```kotlin
val sampleModule = module {
    single { ClassicA() }                    // ❌ today: invisible to walker
    single(named("x")) { ClassicB() }        // ❌ today: invisible to walker
    single<ClassicC>()                       // ✅ safe DSL, always tracked
}

// Helper extensions — even more invisible:
fun Module.createA() = single { ClassicA() }                                   // non-inline
inline fun <reified T> Module.registerProvider() = single<T>()                 // inline reified
inline fun <reified T> Module.registerStore(name: String) {                    // original reporter
    single(named(name)) { Store<T>() }
}

val anotherModule = module {
    createA()                                // walker doesn't expand → ClassicA invisible
    registerProvider<User>()                 // walker doesn't expand → Store<User> invisible
}
class MyVm(val a: ClassicA, val s: Store<User>)   // ❌ KOIN-D001 false positives
```

A pre-1.0.0 trial briefly added direct-classic-DSL tracking (the inside-`module { }` half) but was reverted before shipping: the trial surfaced obstacles with handling user helper functions automatically (see "Trial findings" below) that the lambda-guard half-fix didn't fully address. Better to ship `@KoinExtension` cleanly in 1.0.1 than ship a half-coverage that breaks on helper-using projects.

## Trial findings — why auto-detection turned out hard

1. **Position-based `.bind()` tracking is order-fragile.** `collectBindType` attaches each `.bind(X::class)` to `_dslDefinitions.lastOrNull()` — once we start adding DslDefs from arbitrary places (helper bodies anywhere in the compilation unit), bind calls mis-attach to wrong types and cascade.
2. **The walker can't tell helper-body from module-body without context.** `fun Module.createA() = single { … }` and `module { single { … } }` look identical at the IR call level.
3. **Cross-source-set `visitCall` doesn't fire reliably.** When `jvmTest` source called a helper defined in `jvmMain`, the visitor didn't see the call site at all. Whether this is K2 inlining of expression-body extensions, IR symbol-reference vs IR-call distinction, or something else — needs a focused IR debugging session we don't want to do mid-1.0.0.
4. **Full auto-detection needs**: reified type-arg substitution + const-string value-arg substitution + helper-chain following + cross-module body resolution. Each is bounded but together it's 1–2 days *after* understanding (3).

## The annotation approach

Marking helpers explicitly turns a mind-reading problem into a metadata problem.

```kotlin
@KoinExtension
inline fun <reified T> Module.registerProvider() = single<T>()
```

Why it sidesteps each obstacle:

| Obstacle | Mitigation |
|---|---|
| Position-based `.bind()` cascade | We register helper-expanded DslDefs *at the call site in the calling module's context*, not anywhere we encounter the helper body. The position invariant holds. |
| Helper-vs-module ambiguity | Predicate-based FIR discovery on `@KoinExtension` is exact. No heuristics, no `currentLambda != null` gating. |
| Cross-source-set `visitCall` mystery | FIR pre-computes the helper's provider metadata via a generated `koinhelper_<fqn>(...)` hint function. The IR side reads the registered hint from the symbol provider — no dependency on `visitCall` firing for the helper invocation. |
| Implementation surface | Restricting the helper body to safe DSL (`single<T>()` / `factory<T>()` / etc.) sidesteps classic-DSL lambda walking, const-string substitution, and most edge cases. |

## Design

### Annotation

```kotlin
// koin-annotations / org.koin.core.annotation
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class KoinExtension
```

### FIR phase

Predicate-based scan picks up every `@KoinExtension` function. For each helper, generate a synthetic hint function in `org.koin.plugin.hints`:

```
koinhelper_<sanitized-fqn>(
    contributed: <HelperFunctionType>,    // anchor for resolution
    provider0: <ProvidedClass-or-TypeParamMarker>,
    provider1: …
)
```

Each `providerN` parameter encodes one of the safe-DSL calls inside the helper body:

- **Concrete class** (`single<ClassicA>()`) → parameter typed as `ClassicA`
- **Type parameter** (`single<T>()` where `T` is reified on the helper) → parameter typed as a synthetic marker class `__HelperTypeParam_<index>__` (or similar), index = position in the helper's type parameter list

DefinitionType (SINGLE / FACTORY / SCOPED / VIEW_MODEL / WORKER) encoded in the parameter name prefix.

### IR phase

When `visitCall` encounters a call to a `@KoinExtension` function:

1. Resolve the helper's `koinhelper_*` hint via the symbol provider (works cross-module — hints survive in compiled metadata)
2. For each encoded provider:
   - Concrete class → register `Definition.DslDef(irClass = X, definitionType = Y, …)`
   - Type-param marker → resolve the call site's type argument at the encoded index, register `Definition.DslDef` with that resolved class
3. Apply a `_dslDefinitions.lastOrNull()` dedupe to stay idempotent when a helper call sits near an inner `create(::T)` registration

### Where IR plugs in

The dispatch goes into `KoinDSLTransformer.visitCall`, alongside the existing safe-DSL path:

```kotlin
// Safe DSL today: handleTypeParameterCall    (extension fns under org.koin.plugin.module.dsl)
// NEW: KoinExtension expansion    (callee has @KoinExtension, hint resolvable)
```

If this proposal also lands the classic-DSL-in-`module { }` portion at the same time, it would be a separate FQN-keyed branch (the FQN map approach explored in the trial was sound; only the helper-detection part was problematic).

## Scope for v1

In addition to the helper-expansion described above, v1 of this proposal should also re-introduce the **classic-DSL inside `module { }`** branch that the pre-1.0.0 trial explored (and reverted). That branch is well-understood and uncontroversial; it was reverted only because the trial wasn't fully clean (the helper-extension cascade described above was unresolved). With `@KoinExtension` covering helpers, the direct-classic-DSL detection no longer has an "escape hatch" gap and can ship safely.

## Out of scope for v1

- **Classic DSL inside the helper** (`single(named("x")) { Foo() }` form) — would need qualifier-string-arg substitution to encode `"x"` into the hint. Defer to v2.
- **Helper chains** (`@KoinExtension` A calls `@KoinExtension` B) — hints could recurse but adds design overhead. Defer.
- **`@KoinExtension fun ScopeDSL.…`** inside `scope { }` blocks — should work analogously to `Module` receiver but needs explicit test coverage.

## Estimated effort

- v1 (Module receiver, safe DSL inside, type-arg substitution): ~half-day
- ScopeDSL receiver variant + cross-module hint resolution coverage: ~half-day more

## User-facing example after this lands

Before (today, 1.0.0):
```kotlin
inline fun <reified T> Module.registerProvider() = single<T>()

val m = module { registerProvider<User>() }
class MyVm(val u: User)
// e: [Koin][KOIN-D001] Missing dependency: User (false positive)
```

After (with `@KoinExtension`):
```kotlin
@KoinExtension
inline fun <reified T> Module.registerProvider() = single<T>()

val m = module { registerProvider<User>() }
class MyVm(val u: User)
// ✅ compiles clean — walker expands registerProvider<User> → DslDef(User, SINGLE)
```

## Why the annotation approach over heuristic discovery

| Approach | Pro | Con |
|---|---|---|
| Heuristic (auto-detect any Module ext helper) | Zero user friction | All four trial obstacles are blockers; cascading bind() bugs; unclear scope |
| `@KoinExtension` annotation | Precise, predictable, cross-module-safe, robust to the visitor mystery, narrow scope | Requires explicit user opt-in (small ask for compile-safety win) |

The opt-in cost is paid once per helper, ever. The user explicitly chose this with: *"would have to use the safe DSL part, easier to inject then."* That constraint is what makes the implementation tractable.

## Notes from the pre-1.0.0 trial

The trial spent significant time discovering obstacle (3) — `visitCall` not firing for `examples.createA()` invocations during the `jvmTest` compilation, even though the same compilation produced `KOIN-D001` errors that proved test source was being walked. Possible causes (not investigated):

- K2 inlines expression-body functions at IR phase before our plugin runs
- Cross-source-set extension calls go through a different IR path
- IrCall vs IrSymbolReference distinction we're not handling

The annotation approach **does not require resolving this mystery** because metadata generation happens at FIR (where helper declarations are scanned by predicate, not by visitCall) and IR consumption happens at the *calling* module's processing (where we already reliably see all `visitCall` events).

If the visitor question becomes relevant later for a different feature, it's worth a focused day of IR debugging.
