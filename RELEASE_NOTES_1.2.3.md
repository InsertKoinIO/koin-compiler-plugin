A `KOIN-D001` on a definition from another Gradle module now tells you where to fix it.

## 🐛 Fixed

- **`KOIN-D001` on a definition from another Gradle module had no location.** In a compilation with several Koin graphs, such as a test source set with many `koinApplication { }` calls, nothing said which graph loads it. The error now points at the `modules(...)` call that loads it, and names the `includes()` chain:

  ```
  [Koin][KOIN-D001] Missing dependency: kotlin.time.Clock
    required by: dsl:kernel.SentNotificationsPort (parameter 'clock')
    in module: kernel.notificationsKernelModule
    loaded by: modules() at ToolsTest.kt:60 → toolsModule → useCasesModule → notificationsKernelModule
  ```

- **`KOIN-D001` on a definition whose class lives in another Gradle module had no location.** For `module { single<ServiceImpl>() }`, with `ServiceImpl` from a library, the error now points at the `single<ServiceImpl>()` line.

- **The "Fix with AI" line could point at the wrong file.** It took the location of the last located diagnostic, even a warning in another file. It now follows the error.

## 🔧 Changed

- **`KOIN-D001` gains a `loaded by:` line** for a definition from another Gradle module. A definition of the compiling module keeps its message. No diagnostic is added or removed, and no severity changes.

## ✅ Compatibility

- Koin 4.2.0+
- Kotlin 2.3.20, 2.4.0, 2.4.10, 2.4.20

## 📦 Install

```kotlin
plugins {
    id("io.insert-koin.compiler.plugin") version "1.2.3"
}
```

Your feedback is welcome 🙏

Cheers ✌️

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.2.2...1.2.3
