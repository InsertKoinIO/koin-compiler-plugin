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
