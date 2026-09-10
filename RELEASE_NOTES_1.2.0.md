Compile-time safety now covers the whole Koin DSL, and cross-module `includes` no longer hides definitions.

> On Kotlin 2.4.20? Use 1.2.1. That Kotlin patch removed compiler APIs 1.2.0 depends on.

## ✨ Added

- Constructor-shorthand DSL is validated: `singleOf(::T)`, `factoryOf(::T)`, `scopedOf(::T)`, `viewModelOf(::T)` and `Scope.new(::T)` get the same missing-dependency and qualifier checks as `single<T>()`. Code that relied on an unchecked missing dependency here will now fail to compile. That is the gap closing, not a regression.
- Two more entry points recognized: `KoinApplication.withConfiguration<T>()` and Ktor's `install(Koin) { modules(...) }`.
- `startKoin { modules(myList) }` resolves plain list compositions (`listOf`, `+`, `.toList()`, a function returning `List<Module>`). Runtime-branching lists (`if`/`when`, vararg) stay unresolved, and `KOIN-W003` now points at the call.
- `fun myModule(): Module = module { ... }` is tracked like a top-level `val`.

## 🐛 Fixed

Cross-module visibility. Definitions two or more `implementation` dependency hops away were invisible and produced false `KOIN-D001` errors:

- `@Module(includes = [...])` (#82)
- `@Configuration` auto-discovery (default label)
- DSL `module { includes(...) }`, including through empty aggregator modules
- Koin's plural `binds(...)` was not recognized at all, only `bind`

DSL validation:

- `@Module(includes)` ignored qualifiers when merging definitions: two providers of one type under different `@Named` collapsed into one (#94)
- Reified `bind<I>()` inside `withOptions { }` was silently dropped
- `create(::function)` never validated the function's parameters
- Hand-written lambdas (`single { X(get()) }`) could report a false `KOIN-D004` cycle. Requirements are no longer guessed from the constructor; the lambda's own `get()` calls are validated instead
- A qualified requirement was read as unqualified across a module boundary
- Validation now defers instead of erroring when the module set is known to be incomplete

Also: a module including an incomplete submodule was wrongly marked complete, two DSL definitions of the same shape could crash class loading with a duplicate JVM signature, and lookups of `module()`/`includes()` are now cached (faster builds).

## 🔧 Changed

The Kotlin version warning fires per minor line instead of per patch, so 2.4.10 no longer warns. 1.2.1 reverts this, see its notes.

## ✅ Compatibility

- Koin 4.2.0+
- Kotlin 2.3.20 to 2.4.10 (2.4.20 needs 1.2.1)

## 📦 Install

```kotlin
plugins {
    id("io.insert-koin.compiler.plugin") version "1.2.0"
}
```

Thanks to @javichaques for #94 and to everyone who reported cross-module cases from real apps 🙏

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.1.0...1.2.0
