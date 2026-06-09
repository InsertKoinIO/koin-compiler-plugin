# app-kmp-klib — KLIB target regression sample

A minimal Kotlin Multiplatform app exercising the **KLIB-serialized** targets
(`wasmJs`, `iosArm64`) that the JVM box tests can't cover. It is the repro behind
compiler **#40** (wasmJs) and **#44** (iOS).

`App.kt` declares the same `@InjectedParam` target collected by **two
`@ComponentScan` modules** — the exact shape that emitted the `injectedparams_*`
hint twice and broke KLIB serialization.

## Build

```bash
# from the repo root: publish the plugin to mavenLocal first
./install.sh

cd playground-apps/app-kmp-klib
../../gradlew compileKotlinIosArm64                          # Kotlin 2.3.20 (floor)
../../gradlew compileKotlinIosArm64 -PkotlinVersion=2.4.0
../../gradlew compileKotlinWasmJs
../../gradlew compileKotlinWasmJs  -PkotlinVersion=2.4.0
```

## Expected results

| Target | Kotlin 2.3.20 | Kotlin 2.4.0 |
|---|---|---|
| `iosArm64` (annotations) | ✅ | ✅ |
| `wasmJs` (DSL) | ✅ | ✅ |
| `wasmJs` (annotations / `@ComponentScan`) | ❌ `KT-82395` | ✅ |

- **iOS** and **wasmJs-DSL** pass on both Kotlin versions — the duplicate-signature
  fix (single hint per target) and the IR-hint source-attribution fix.
- **wasmJs + annotations on Kotlin 2.3.20** fails with
  `IllegalStateException: No file found for source null`. This is a **Kotlin 2.3.20
  compiler bug** ([KT-82395](https://youtrack.jetbrains.com/issue/KT-82395)) in the
  JS/Wasm KLIB metadata serializer: the FIR cross-module discovery hints are placed
  in a framework-created file with no resolvable source. The Native backend tolerates
  it; the 2.3.20 wasm/js backend does not. **Fixed by Kotlin 2.4.0** — use Kotlin
  2.4.0 for annotation-based wasmJs.

> Not wired as a CI gate: the wasmJs-annotation cell is Kotlin-version-dependent by
> design (passes on 2.4.0, blocked by KT-82395 on 2.3.20).
