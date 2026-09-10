Kotlin 2.4.20 support, plus two fixes found on real KMP apps.

## ✨ Added: Kotlin 2.4.20

On 1.2.0, any project on Kotlin 2.4.20 failed with a raw `NoSuchMethodError` during IR generation (#89, #99). Kotlin 2.4.20 removed or re-signed four compiler APIs the plugin used. All four are fixed in the plugin core, no new adapter needed.

## 🐛 Fixed

- KLIB serialization failed on wasmJs, JS and Kotlin/Native with `Different declarations with the same signatures were detected` on `componentscan_*` hints. A `@Configuration` module relayed through a dependency could emit the same hint twice. Duplicates are now made distinct instead of dropped. JVM and Android were not affected.
- Two `@Named` providers of one type collapsed into one when read across modules, giving a false `KOIN-D001` (same class of bug as #94 in 1.2.0). If you turned `compileSafety` off because of false D001 errors, re-check with it on.

## 🔧 Changed: Kotlin version check is strict again

1.2.0 trusted a whole Kotlin minor line once one patch was verified. Kotlin 2.4.20 broke that assumption three weeks later, and the relax had silenced the only warning that would have hinted at it. A Kotlin version now counts as verified only when that exact version has been checked with `tools/abi-check`. Unverified versions warn and proceed, they never block.

## ✅ Compatibility

- Koin 4.2.0+
- Kotlin 2.3.20, 2.4.0, 2.4.10, 2.4.20

## 📦 Install

```kotlin
plugins {
    id("io.insert-koin.compiler.plugin") version "1.2.1"
}
```

## Thanks

@jamesarich measured all four API breaks with `abi-check`, validated a fix on a large KMP project and opened #100. This release is built on that analysis. Thanks also to @nagatsuka-shuuya and @annotation-engine for the reports (#89, #99), and to @VladimirPupavaESET, @dmitry-stakhov and @Komdosh for confirming and triaging.

Your feedback is welcome 🙏

Cheers ✌️

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.2.0...1.2.1
