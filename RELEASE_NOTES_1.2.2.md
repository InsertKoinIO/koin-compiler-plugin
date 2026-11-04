Compile safety on Kotlin/Native and wasm/JS now sees your whole graph, and multi-module projects get far fewer false errors.

## ✨ Added

- **Hidden dependency check (opt-in).** Compile safety checks the graph from what the module calling `startKoin` compiles against. A module reached only through an `implementation` dependency of a dependency isn't fully checked. With `koinCompiler { hiddenDependencyCheck = "warning" }` (or `"error"`), the build lists those modules with their path and how to declare them. JVM and Android. Off by default for now: with AGP and no configuration cache, it adds configuration-time resolution warnings.

## 🐛 Fixed

- **Native, wasm and JS:** definitions from another Gradle module were invisible to compile safety. You got a false `KOIN-D002` on valid code, or a real missing dependency passed the build (#105, #106, #113). If you turned `compileSafety` off for these targets, turn it back on.
- **False `KOIN-D001` / `KOIN-D004` across modules** on DSL parameters that need no provider: default value, nullable, `List<T>`, `Lazy<T>`, `@InjectedParam`, `@Property`, `@Provided`, `@ScopeId` (#115). Also on qualified function providers of the same type (#96), on a `@Single fun` provider two modules away (#114), on two `@Named("x")` providers of different types, and on definitions in a `private fun Module.x()` helper.
- **`startKoin<MyApp>` loading a DSL module from another Gradle module** in `modules(...)`: an annotated class needing a type that this module declares with `single` got a false `KOIN-D001` (#124). A `factory` or `scoped` provider there still needs `@Provided` for now.
- **`@Single(binds = [X::class])` on a class from another module** lost its binding: false `KOIN-D001`, and `getAll<X>()` empty at runtime (#98).
- **Modules reported as not loaded:** false `KOIN-W001` after moving a module to another package, and on `includes()` chains of 3 modules or more. A function that returns one of several modules no longer makes them all unloaded (false `KOIN-D002`).
- **Crashes:** "No classId" on local classes and anonymous objects (#101), invalid IR (fatal under `-Xverify-ir`) when a definition in another module targets an `internal` class (#74), and the Kotlin daemon running out of memory on multi-module builds (#110).
- **Configuration cache** was thrown away on every Kotlin edit.
- False `KOIN-D002` on a `Koin` built elsewhere (#58), false `KOIN-D006` on `activityViewModel<T>()` (#121).

## 🔧 Changed

- **More of your graph is checked**: Native and wasm/JS, `includes()` chains of 3+ modules, entry points with an empty `@ComponentScan` in a dependency. A green build can now fail on a real missing dependency.
- **`strictSafety = false` is honored** on the module calling `startKoin`, with a warning (#120). `strictSafetyForceOff` is deprecated. With `compileSafety = false`, strictSafety no longer scans or logs (#88).
- **strictSafety now detects AGP 9 modules.** With AGP 9's built-in Kotlin, the module calling `startKoin` was never detected. It now is, so it recompiles on every build like on other setups. `strictSafety = false` turns that off.
- **Explicit `binds` across modules:** the class is bound to exactly its `binds`, as within one module (#98).
- **New warnings:** `KOIN-W008` (a module's definitions can't be seen, check skipped), `KOIN-W009` (a call on a `Koin` from your own code), `KOIN-W010` (`activityViewModel<T>()` without `parametersOf()`), `KOIN-W011` (a module chosen at runtime). They fail builds that use `allWarningsAsErrors`. A `startKoin` module with no DSL definition of its own now logs that its DSL graph isn't validated.
- **KMP libraries checking their KLIB ABI** (`apiCheck`): run `apiDump` once for the new `org.koin.plugin.hints` entries.
- **Libraries built with 1.1.0 to 1.2.1:** rebuild them with 1.2.2 to get the #96 fix for their function providers.

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

@PhBogdanov traced the KLIB root cause down to the Kotlin serializer across four Kotlin versions (#106), and found and fixed the daemon memory leak (#111). @shreknitin89 (#116), @cpaleop (#90) and @paulnunezm (#128) sent fixes this release builds on.

Thanks to everyone who reported, reproduced and retested: @acmpo6ou, @af-jul, @ahmedhappa, @alexandru-calinoiu, @andrew-turner-control4, @aoreshkov, @axiel7, @brueggenthies-adesso, @dakatso, @dmitry-stakhov, @emartynov, @exoego, @Faltenreich, @GoodSir42, @hmy65, @ikurek, @imcmib, @JajaComp, @jamesarich, @kfaraj, @Komdosh, @MattSkala, @nabiu256, @nagatsuka-shuuya, @oscardavison, @PhilippNowak96, @renaudmathieu, @sanders9, @smartine-ledger, @stzouvaras, @tylerwilson, @VladimirPupavaESET, @xephosbot, @yaroslav-v and @zjarlin.

Your feedback is welcome 🙏

Cheers ✌️

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.2.1...1.2.2
