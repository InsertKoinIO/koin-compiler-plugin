# app-kmp-klib-hints — cross-module hints on KLIB targets

Regression check for #105, #106 and #113: definition hints generated in IR never reached the KLIB
(Kotlin 2.4.20, KT-79565; partly already on 2.4.10 and earlier), so on wasmJs / iosArm64 a downstream entry point saw an
empty graph — false `KOIN-D002`, or a real missing dependency passing silently. `jvm` is the control.

| Module | Shape | Issue |
|---|---|---|
| `:lib-config` | `@ComponentScan` + `@Configuration` in `commonMain`, a second `@Module` in each platform source set | #105 |
| `:lib-plain` | `@ComponentScan` without `@Configuration` | #106 |
| `:lib-dsl` | classic DSL `single<T> { … }` | #113 |
| `:app` | `@KoinApplication` + `startKoin<App> { modules(…) }`, looks up every type above | valid graph |
| `:app-falsify` | entry point over `:lib-config`; `-Pfalsify=true` adds a definition with a missing dependency | must fail with `KOIN-D001` |
| `:lib-wide` / `:app-wide` | one shape per IR-built hint kind (`componentscan_`, `componentscanfunc_`, `funcreqs_`, `annotationincludes_`, `injectedparams_`, `callsite`, `dsl_`, `dslincludes_`) | `./verify-wide.sh <version>`: each KLIB target must report exactly what jvm reports (D001 `Tire`, D003 `AppOnly`, D006 `Greeter`) |
| `:lib-stale` / `:app-stale` | `-Pstale=true`: dependency pinned to plugin 1.2.1 (no hints in its KLIB) | must warn `KOIN-W008`, never pass silently |

## Run

```bash
# from the repo root: publish the plugin under test
./gradlew publishToMavenLocal --no-configuration-cache -PpluginVersion=<version>

cd playground-apps/app-kmp-klib-hints
./verify.sh <version> 2.3.20 2.4.0 2.4.10 2.4.20
../../gradlew :app:wasmJsNodeDevelopmentRun :app:linkDebugFrameworkIosArm64 -PkoinPluginVersion=<version>   # prints KLIB-HINTS-RUNTIME-OK
```

## Expected

| | 1.2.1 | fixed |
|---|---|---|
| valid graph, wasmJs / iosArm64 | false `KOIN-D002` (`LoggerService`, `Clock`; plus `Counter` on 2.4.20) | PASS |
| broken graph, wasmJs / iosArm64, Kotlin 2.4.20 | silent PASS | `KOIN-D001` |
| jvm, any Kotlin | correct | unchanged |

wasmJs on Kotlin 2.3.20 fails with `No file found for source null` on every plugin version: `@ComponentScan` on wasm
requires Kotlin 2.4.0 (KT-82395).
