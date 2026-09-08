Relaxes the Kotlin-version-compatibility warning from exact-patch to minor-line granularity.

## Behavior change — "newer than tested" warning now fires per minor line, not per patch

**Why.** The warning compared the running compiler's full `major.minor.patch` against the newest
registered adapter, so every new Kotlin patch in an already-supported line (e.g. 2.4.10, 2.4.20 —
registered adapter: 2.4.0) warned as "unverified," even though `koin-compiler-version-adapter`
selects and reuses that same line's adapter regardless. Verified via `tools/abi-check` that Kotlin
2.4.10 has zero binary breaks against the plugin's compiler-API usage.

**What changed:** the warning now fires only when the compiler is on a Kotlin minor line with no
registered adapter (e.g. 2.5.0) — not on a patch bump within a registered line. Adapter selection
itself is unchanged.

**Process note:** this trusts that a new patch within a supported line stays compatible. Verify
new patches with `tools/abi-check/check-kotlin-abi.sh <version>` before relying on it — see
CLAUDE.md's version-gate policy.

## Added — Kotlin 2.4.20 support (`koin-compiler-version-adapter:kotlin-2.4.20`)

**Why.** The minor-line warning relaxation above was verified against Kotlin 2.4.10 only, and its
own process note says to run `tools/abi-check/check-kotlin-abi.sh` on each new patch before
relying on it. Running it against 2.4.20 finds **four binary breaks** in the plugin core, so
2.4.20 does *not* stay compatible within the 2.4 line, and without a registered adapter the
relaxed warning would have suppressed the only diagnostic pointing at the cause. Reported as #89.

**Breaks found** (plugin core, 416 compiler-API refs; 0 missing on 2.3.20/2.4.0/2.4.10):

- `IrUtilsKt.getValueArgument(IrConstructorCall, Name)` — removed. 9 call sites.
- `IrAnnotationImpl.setArgumentMapping(Map)` — removed; `argumentMapping` is now a read-only
  `IrAnnotationArgsView` computed from `arguments` + `symbol`.
- `IrFactory.createSimpleFunction(...)` — gained a trailing `IrClassSymbol` parameter. 12 call sites.
- `FirResolvedQualifier.classId` — removed; `symbol` renamed to `qualifierSymbol`.

**What changed:**

- New `kotlin-2.4.20` adapter module, registered in `kotlin-version-adapters.properties` and
  `supported-kotlin-versions.txt`, embedded in the published jar like the existing two.
- Three new `KotlinVersionAdapter` members for the version-split operations —
  `createSimpleFunction`, `setAnnotationArgumentMapping`, `classIdOf` — each annotated with
  `@KotlinApiChange`. The `createSimpleFunction` member exposes only the parameters that actually
  vary across call sites; the invariant flags are fixed in each adapter, so generated code is
  unchanged.
- `getValueArgument(Name)` replaced by a name-based `getRegularArgument(Name)` in
  `IrArgumentMapping.kt`, alongside the existing index-based one. No adapter needed: it is built
  on `regularParameters`, which is stable across every supported line.

**Verification.** `check-kotlin-abi.sh` reports 0 missing for the plugin core on **2.3.20, 2.4.0,
2.4.10 and 2.4.20**, and each adapter module resolves cleanly against its own target. Full test
suite green (372 tests) with **no golden-file changes** — the transformations emit identical IR.
