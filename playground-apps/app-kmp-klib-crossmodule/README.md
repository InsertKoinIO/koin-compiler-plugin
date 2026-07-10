# app-kmp-klib-crossmodule — cross-module @ComponentScan KLIB regression sample

A two-module Kotlin Multiplatform app proving that a `@ComponentScan` covering a **dependency
module's** package emits each scanned definition exactly once on KLIB-serialized targets
(`wasmJs`, `iosArm64`).

- `:feature` — declares `@Single class CharactersApiService` / `EpisodesApiService`.
- `:app` — `@Module @Configuration @ComponentScan("crossmodule.feature")`, depends on `:feature`.

The scanned classes reach `:app` only through `:feature`'s generated definition hints. Before the
fix (`findMatchingDefinitions` returned `localDefinitions + crossModuleDefinitions` undeduplicated),
the same definition was added once per discovery path, so `:app` emitted the
`componentscan_..._single(...)` hint — and the matching `single { }` registration — multiple times.

On JVM/DEX that is only a D8 *"multiple definitions"* warning. On KLIB it is a **hard compile
error** — this is the only target that catches it.

## Build

```bash
# from the repo root: publish the plugin to mavenLocal first
./install.sh

cd playground-apps/app-kmp-klib-crossmodule
../../gradlew :app:compileKotlinWasmJs   -PkotlinVersion=2.4.0
../../gradlew :app:compileKotlinIosArm64 -PkotlinVersion=2.4.0
```

The build defaults to the shipping plugin version. `@ComponentScan` on wasmJs requires
Kotlin 2.4.0 (KT-82395), hence the `kotlinVersion` default.

## Expected results

| Plugin version | wasmJs / iosArm64 |
|---|---|
| `1.0.1` (pre-fix) | ❌ `SignatureClashDetector`: multiple identical `componentscan_crossmodule_app_NetworkModule_single(contributed:…)` declarations |
| `1.0.2` (fixed) | ✅ one declaration per scanned class |

To reproduce the failure against the pre-fix build, override the pinned version:

```bash
../../gradlew :app:compileKotlinWasmJs -PkotlinVersion=2.4.0 -PkoinPluginVersion=1.0.1
```
