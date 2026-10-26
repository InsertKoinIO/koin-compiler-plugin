Compile safety on Kotlin/Native and wasm/JS now sees your whole graph.

## 🐛 Fixed

- On Kotlin/Native, wasm and JS, definitions from another Gradle module were invisible to compile safety: `@ComponentScan` classes, DSL definitions, `@InjectedParam` shapes and deferred call sites. You got a false `KOIN-D002` on valid code, or a real missing dependency passed the build. Always the case on Kotlin 2.4.20, and on earlier versions for modules without `@Configuration` (#105, #106, #113). If you turned `compileSafety` off for your Native or wasm targets, turn it back on. JVM and Android were not affected.

## 🔧 Changed

- **Real errors now reported on Native and wasm/JS.** Missing dependencies, missing `parametersOf()` and deferred call sites are now checked the same way as on JVM. A build that was green on these targets can now fail on a real problem.
- **New `KOIN-W008` warning.** When the plugin can't see a module's definitions, it now says so and names the module instead of skipping the check silently. Typical causes: the module was built with an older plugin version, its `@ComponentScan` matches nothing, or it is not a direct dependency. This applies to every target, JVM included, and fails builds that use `allWarningsAsErrors`.
- **KMP libraries checking their KLIB ABI** (`apiCheck`) will see new `org.koin.plugin.hints` entries once. Run `apiDump`. These hints were always meant to be published; on JVM they already were.

## ✅ Compatibility

- Koin 4.2.0+
- Kotlin 2.3.20, 2.4.0, 2.4.10, 2.4.20

## 📦 Install

```kotlin
plugins {
    id("io.insert-koin.compiler.plugin") version "1.2.2"
}
```

## Thanks

@PhBogdanov traced the root cause down to the Kotlin KLIB serializer with KLIB dumps across four Kotlin versions (#106). Thanks to @dmitry-stakhov, @GoodSir42 and @sanders9 for the reports and reproducers (#105, #106, #113).

Your feedback is welcome 🙏

Cheers ✌️

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.2.1...1.2.2
