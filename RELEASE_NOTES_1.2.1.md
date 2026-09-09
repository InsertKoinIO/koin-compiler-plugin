A compatibility patch for **Kotlin 2.4.20**, which removed or re-signed four compiler APIs the
plugin binds to. On 1.2.0 that surfaced as a raw `NoSuchMethodError` on every target, with no
diagnostic from us to explain it.

This release also reverses the version-check relax that shipped in 1.2.0. That change trusted a
whole Kotlin minor line once one patch in it was verified, and Kotlin 2.4.20 proved the assumption
wrong three weeks later. Details below, including what it costs you.

## ✨ Added: Kotlin 2.4.20 support

Kotlin 2.4.20 is now a verified version. On 1.2.0 and 1.1.0, any project on it failed during IR
generation:

```
e: org.jetbrains.kotlin.fir.pipeline.IrGenerationExtensionException:
   'org.jetbrains.kotlin.ir.expressions.IrExpression
    org.jetbrains.kotlin.ir.util.IrUtilsKt.getValueArgument(
      org.jetbrains.kotlin.ir.expressions.IrConstructorCall,
      org.jetbrains.kotlin.name.Name)'
Caused by: java.lang.NoSuchMethodError: ...IrUtilsKt.getValueArgument(...)
```

Depending on which path your project reaches first you may instead see
`IrFactory.createSimpleFunction(...)` from `DslHintGenerator`. Both are the same cause.

The plugin core compiles against the 2.3.20 floor, so all four changes were binary breaks. Nothing
showed up at build time, only when a user ran 2.4.20:

- `IrFactory.createSimpleFunction`: gained a trailing `IrClassSymbol` parameter (13 call sites).
- `IrUtilsKt.getValueArgument(IrConstructorCall, Name)`: removed (9 call sites).
- `FirResolvedQualifier.classId`: removed, and `symbol` renamed to `qualifierSymbol`.
- `IrAnnotationImpl.setArgumentMapping`: removed, the map is now derived from the annotation's
  arguments.

No new adapter module was needed. The existing 2.4.0 adapter is already binary-clean against
2.4.20, so all four fixes are in the plugin core and 2.4.20 reuses that adapter class.

## 🐛 Fixed: duplicate relayed hints broke KLIB serialization on wasm and native

A multiplatform project could fail to serialize its KLIB on 1.2.0:

```
AssertionError: Different declarations with the same signatures were detected
 - FUN name:componentscan_<Module>_single (contributed:X, binding0:I)
 - FUN name:componentscan_<Module>_single (contributed:X, binding0:I)
```

Introduced in 1.2.0 by the `@Configuration` relay. When a consumer module reaches a
`@Configuration` module through a dependency, it re-publishes that module's definition hints into
its own output. For some classpath shapes the same definition arrived twice, and the two copies got
different internal dedupe keys while producing an identical hint signature, so both were emitted.

Two hints with the same signature are a hard error only when a KLIB is serialized, so this was
**invisible on JVM and Android** and hit wasmJs, JS, and Kotlin/Native. It also only shows up in
the module that both relays and serializes: the module owning the `@Configuration` class compiled
fine.

Colliding hints are now made distinct by an extra marker parameter rather than deduplicated, so no
hint is ever discarded: the erased signature is not a safe identity here, because a `@Named`
qualifier's value is carried in a parameter name. This covers every hint category at once.

The mechanism producing the duplicate in the first place is not yet identified; this makes the
duplicate harmless rather than preventing it, and that is tracked as a follow-up rather than
claimed as fixed. Reported against a real KMP app, whose full module set (JVM, iosArm64,
iosSimulatorArm64, wasmJs, Android, desktop) now compiles and runs clean.

## 🐛 Fixed: two `@Named` providers of one type collapsed into one across module boundaries

When a module's definitions were read back from its generated hints, they were deduplicated on the
provided **type alone**, ignoring the qualifier. Two providers of the same type under different
qualifiers is ordinary Koin:

```kotlin
@Single @Named("baseUrl") fun provideBaseUrl(): Url = ...
@Single @Named("authUrl") fun provideAuthUrl(): Url = ...
```

Read cross-module, the second was discarded, and any consumer needing it failed with a false
`KOIN-D001 Missing dependency` on a graph that resolves correctly at runtime, sometimes with the
hint `Found similar binding: Url with qualifier @Named(...)` naming the provider that had just been
dropped. Now keyed on (type, qualifier), matching the five sibling sites that were already fixed
this way for `@Module(includes = [...])` in 1.2.0 (#94).

**This can surface definitions that were previously invisible**, so a project that had turned
`compileSafety` off because of false `KOIN-D001`s is worth re-checking with it on.

Three narrower qualifier-blind sites remain in the same area, on paths no reproducer currently
reaches. They are tracked rather than changed blind.

## ⚠️ Changed: the Kotlin version check is per exact version again

1.2.0 relaxed the compatibility warning from exact patch to minor line, on the strength of an
`abi-check` run against 2.4.10. The reasoning was that a patch inside a verified line stays
compatible. Kotlin 2.4.20 falsified it: four removed APIs, and because 2.4.20 sits inside the
already-registered 2.4 line, the relax had also silenced the one warning that would have hinted at
the cause.

Kotlin ships feature releases in the `.20` patch slot. This plugin's own floor, 2.3.20, is one of
them. So the minor line was never a safe unit of trust, and 1.2.1 splits the two concerns that
1.2.0 had merged:

- **Adapter selection is unchanged:** a version loads the highest registry entry at or below it, so
  several verified versions can share one adapter class. 2.4.10 and 2.4.20 both use the 2.4.0
  adapter.
- **Trust is per exact version:** a Kotlin version counts as verified only when it has its own
  entry in `supported-kotlin-versions.txt`, earned by a green
  `tools/abi-check/check-kotlin-abi.sh <version>` run against both the core and the adapter it
  selects.

**What this costs you:** an unverified patch release warns again where 1.2.0 stayed quiet. If you
move to a Kotlin patch we have not verified yet, you will see a line naming your version and the
verified list, and the build proceeds on the closest adapter. That is noisier than 1.2.0. We think
a warning you can read beats a crash you cannot.

2.4.10 also gains its own registry entry in this release, which is why moving to it stays silent
under the stricter rule.

The warning text changed with it: it now says "Verified versions:" where it said "Supported
versions:", adds a "not among the verified versions" wording for a version inside a registered
line, and names the adapter actually selected rather than always the newest. Worth knowing if you
match on that string in CI.

Adapter selection itself is unchanged, and `versionCheckSeverity = "info"` still downgrades this
warning if it blocks a build compiled with `-Werror`.

## 🔧 Internal: making this class of break less likely

Both changes are behavior-preserving. The golden files (`*.fir.ir.txt`) are unchanged, which is how
we check that.

- **Function creation goes through the compiler's own builder:** the 13 direct
  `IrFactory.createSimpleFunction` calls now use `buildFun { }`, which invokes
  `createSimpleFunction` from inside the compiler jar. Signature churn there now resolves against
  the running compiler instead of against whatever we compiled with. `buildFun` and
  `IrFunctionBuilder` are byte-identical across 2.3.20 to 2.4.20.
- **`abi-check` now covers the adapter too:** it previously checked only the plugin core, so an
  adapter-level break would have gone unseen. It now also checks the adapter class that the target
  version actually selects.

## ✅ Compatibility

- **Koin**: 4.2.0+
- **Kotlin**: 2.3.20, 2.4.0, 2.4.10, 2.4.20

## 📦 Install

```kotlin
plugins {
    id("io.insert-koin.compiler.plugin") version "1.2.1"
}
```

## Thanks

Thanks to @jamesarich, who measured all four breaks with `abi-check`, validated a fix on a 17k-file
KMP project, reported that `check-kotlin-abi.sh` was missing from CI, and opened PR #100 with a
complete implementation. That PR is not what shipped here: 1.2.1 fixes all four breaks in the
plugin core, which left the existing 2.4.0 adapter untouched, where #100 added members to a new
adapter module. His measurement and analysis are what this release is built on, and the
version-gate reasoning below is his argument.

Thanks to @nagatsuka-shuuya and @annotation-engine for the reports (#89, #99),
@VladimirPupavaESET for confirming the crash still reproduced on released 1.2.0, @dmitry-stakhov
for the triage, and @Komdosh for confirming the versions affected.

Your feedback is welcome 🙏

Cheers ✌️

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.2.0...1.2.1
