Patch release: two regressions reported on 1.2.2, a location for cross-module `KOIN-D001`, and compile safety that says, on the line, which DSL shape it could not follow.

## 🐛 Fixed

- **Native, JS and wasm compilations crashed on 1.2.2** with `Different declarations with the same signatures` when two DSL modules provided two `internal` types of the main source set, or the same type twice (#144).
- **False `KOIN-D001` for a `factory<T>()` from another Gradle module** under `startKoin<T>` / `@KoinApplication`; 1.2.2 fixed it for `single` only (#143). A `factory` inside a `scope { }` block stays scoped. Rebuild the library with 1.2.3 to get it.
- **`KOIN-D001` on a definition from another Gradle module had no location.** With `startKoin { modules(...) }` it now points at the `modules(...)` call that loads it, with the chain: `loaded by: modules() at ToolsTest.kt:60 → toolsModule → useCasesModule → notificationsKernelModule`. A local `single<LibImpl>()` of a library class points at that line.
- **The "Fix with AI" line could point at the wrong file**, or at another compilation's error under parallel builds (#140). It follows the error.
- **Diagnostics anchored on a declaration or a `modules()` call showed the file only in the Gradle output**, no line and column, so a `KOIN-D001` or `KOIN-W001` could not be clicked to. Every Koin diagnostic now carries `file:line:column`.

## ✨ Added

- **Compile safety discloses the DSL shapes it cannot follow**, on the line, with the shape to use instead: `KOIN-W012` for an `includes()` inside an inline `module { }` or a public `fun Module.x()` helper (its modules are reported unloaded and their types missing), `KOIN-W014` for a private helper called on an explicit receiver or as a function reference, `KOIN-W015` for a `module { }` in `by lazy`, a lambda or a list builder. `KOIN-W013` says an entry point in a module with no DSL definition of its own gets no DSL graph check (#142); no rewrite yet. Followed shapes: [docs/COMPILE_TIME_SAFETY.md](docs/COMPILE_TIME_SAFETY.md#dsl-shapes-compile-safety-understands). Like every `KOIN-W`, they fail a build with `allWarningsAsErrors`.

## 🔧 Changed

- **`modules(AppModule().module())` is now verified** like `modules(AppModule::class)`. A missing dependency in such a graph now reports `KOIN-D001`.
- **`KOIN-W003` points at the operand it could not read and names it** (the parameter under a `*spread` or a `+`, not the spread or the `plus()`); when that operand is a parameter of the enclosing function, it says to start Koin where the modules are known. `KOIN-W001` points at the module declaration.
- **`KOIN-W012` and `KOIN-W015` are INFO in a compilation that starts no Koin**, since what they describe can only happen in the module that does; they stay warnings there. One warning per module block.

## ✅ Compatibility

- Koin 4.2.0+
- Kotlin 2.3.20, 2.4.0, 2.4.10, 2.4.20

## 📦 Install

```kotlin
plugins {
    id("io.insert-koin.compiler.plugin") version "1.2.3"
}
```

## Thanks

@richmenaft (#144), @JajaComp (#143) and @renaudmathieu (#142) for the reports and reproducers.

Your feedback is welcome 🙏

Cheers ✌️

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.2.2...1.2.3
