Adds a Kotlin 2.4.20 adapter. 1.2.0 declares support for the 2.4 line but crashes on 2.4.20:
four compiler APIs the plugin core binds were removed or re-signed in that patch, and the
minor-line warning relaxation shipped in 1.1.1 silenced the one diagnostic that pointed at it.

## Fixed - Kotlin 2.4.20 crashes the plugin (#89)

**Symptom.** Every Kotlin compile task fails with `IrGenerationExtensionException` wrapping a
`NoSuchMethodError`, most often:

```
Caused by: java.lang.NoSuchMethodError: 'org.jetbrains.kotlin.ir.expressions.IrExpression
 org.jetbrains.kotlin.ir.util.IrUtilsKt.getValueArgument(
   org.jetbrains.kotlin.ir.expressions.IrConstructorCall, org.jetbrains.kotlin.name.Name)'
	at org.koin.compiler.plugin.ir.KoinAnnotationProcessor.getExplicitBindings
```

**Why 1.2.0 did not fix it.** 1.1.1 relaxed the "newer than tested" warning from exact-patch to
minor-line granularity, on the strength of an `abi-check` of 2.4.10. Its own process note says to
run `tools/abi-check/check-kotlin-abi.sh` against each new patch before relying on that. Run
against 2.4.20 it reports four binary breaks, so 2.4.20 does not stay compatible within the 2.4
line. With no registered 2.4.20 adapter, the relaxed warning stayed silent.

**Breaks found** in the plugin core (0 missing on 2.3.20, 2.4.0 and 2.4.10):

- `IrUtilsKt.getValueArgument(IrConstructorCall, Name)`, removed.
- `IrAnnotationImpl.setArgumentMapping(Map)`, removed. `argumentMapping` is now a read-only
  `IrAnnotationArgsView` computed from `arguments` + `symbol`.
- `IrFactory.createSimpleFunction(...)`, gained a trailing `IrClassSymbol` parameter.
- `FirResolvedQualifier.classId`, removed. `symbol` renamed to `qualifierSymbol`.

**What changed:**

- New `kotlin-2.4.20` adapter module, registered in `kotlin-version-adapters.properties` and
  `supported-kotlin-versions.txt`, embedded in the published jar like the existing two.
- Three new `KotlinVersionAdapter` members for the version-split operations
  (`createSimpleFunction`, `setAnnotationArgumentMapping`, `classIdOf`), each annotated with
  `@KotlinApiChange`. The `createSimpleFunction` member exposes only the parameters that actually
  vary across call sites. The invariant flags are fixed in each adapter, so generated code is
  unchanged.
- `getValueArgument(Name)` replaced by a name-based `getRegularArgument(Name)` in
  `IrArgumentMapping.kt`, alongside the existing index-based one. No adapter needed: it is built
  on `regularParameters`, which is stable across every supported line.
- `KotlinAdapterLoaderTest` now pins the shipped registry: 2.4.20 must select the 2.4.20 adapter,
  and 2.4.10 must stay on 2.4.0. An adapter that is registered but never selected reproduces this
  crash silently, so the mapping is asserted rather than assumed.

Adapter selection is unchanged for everyone else. 2.4.10 and other 2.4 patches below 2.4.20 keep
using the 2.4.0 adapter.

**Verification.** `check-kotlin-abi.sh` reports 0 missing for the plugin core on 2.3.20, 2.4.0,
2.4.10 and 2.4.20 across 415 compiler-API refs, and each adapter module resolves against its own
target. All unit tests pass. The `JvmBoxTestGenerated` golden IR dumps are not a signal here: 94
of them already fail on an unmodified `main`, and this change leaves that set unchanged, test for
test.
