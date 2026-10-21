# AGENTS.md

Instructions for AI coding agents (Codex, Cursor, Copilot, Junie, Claude Code…)
working on this repository. Deeper contributor notes — strict safety, release
notes, version gate — live in [CLAUDE.md](CLAUDE.md); read it before changing
the plugin's behavior.

## What this repo is

The Koin Compiler Plugin: a native Kotlin (K2) compiler plugin that compiles
both Koin styles — Koin DSL and Koin Annotations — and checks the whole
dependency graph at build time. No KSP, no kapt, no generated files. It
replaces the deprecated Koin KSP processor (`koin-ksp-compiler`).

- Plugin id: `io.insert-koin.compiler.plugin` — current version in
  `gradle.properties` (`pluginVersion`)
- Kotlin: K2, 2.3.20 minimum; verified versions in `supported-kotlin-versions.txt`
- Koin: 4.2.0+ (users: latest Koin BOM)

## Project layout

```
koin-compiler-plugin/            # Compiler plugin (FIR + IR phases), tests in testData/
koin-compiler-gradle-plugin/     # Gradle plugin, koinCompiler { } extension
koin-compiler-version-adapter/   # Per-Kotlin-line adapters (kotlin-2.3.20, kotlin-2.4.0)
test-apps/                       # Separate Gradle build: KMP sample + multi-module test
playground-apps/                 # Compile-safety stress-test apps (app-annotations, app-dsl…)
docs/                            # Design and user docs
```

## Build and test

```bash
./gradlew build --continue                 # what CI runs (JDK 21)
./test.sh                                  # compiler plugin tests (:koin-compiler-plugin:test --rerun-tasks)
./test.sh --tests "*SingleBasic*"          # specific tests
./test.sh -Pupdate.testdata=true           # update golden files — only for intended output changes
./install.sh                               # publish to Maven Local (needed by test-apps / playground-apps)
cd test-apps && ./gradlew :sample-app:jvmRun
```

## Rules for changes

- **Public API** — `koinCompiler { }` options, supported DSL functions,
  annotation semantics and the generated-code behavior are API. No removal or
  behavior change without a migration path; call any behavior change out in
  the PR.
- **Golden files** (`*.fir.txt`, `*.fir.ir.txt`) are the review surface. An
  unexpected diff in an unrelated test is a regression. Never blanket-regenerate
  to make CI green.
- **Tests for every case** — a bug fix gets a box test in
  `koin-compiler-plugin/testData/box/<area>/` that fails before the fix and
  returns `"OK"` after; a new transformation gets box tests + golden files.
- **Codegen changes: JVM-green is not done** — also compile `test-apps` for a
  native target (e.g. `iosArm64`) and `wasmJs`.
- **Silent is worse than broken** — never drop a definition, qualifier or
  binding without a diagnostic.
- Full PR guards and the compile-safety stress test: "PR Guards" in
  [CLAUDE.md](CLAUDE.md) and [playground-apps/README.md](playground-apps/README.md).

## Koin terminology — keep docs, tests and samples aligned

These names match the official Koin skills
([InsertKoinIO/koin-skills](https://github.com/InsertKoinIO/koin-skills)) and
https://insert-koin.io/docs/intro:

- **Two styles, one container**: **Koin DSL** or **Koin Annotations**
  (`@Singleton`, `@Factory`, `@KoinViewModel`), JSR-330 compatible. One style
  per project; no mixing unless asked.
- **Three DSL forms**, all compiled and checked by this plugin:
  - **Compiler Plugin DSL** — `single<T>()`, `single { create(::T) }` — recommended with the Compiler Plugin
  - **Constructor DSL** — `singleOf(::T)`
  - **Classic DSL** — `single { T(get()) }`
- Compiler Plugin DSL primitives and the reified entry points (`startKoin<App>`,
  `koinApplication<App>`, `koinConfiguration<App>`, `module<T>()`) import from
  `org.koin.plugin.module.dsl`. Constructor DSL `*Of` lives in `org.koin.core.module.dsl`.
- Prefer `@Singleton` over `@Single` in examples.
- Never suggest KSP for Koin: no `koin-ksp-compiler`, no
  `org.koin.ksp.generated.*`, no `AppModule().module` (use `module<AppModule>()`).
- Link Koin docs with full `https://insert-koin.io/docs/...` URLs.

User-facing summary of all of the above: [llms.txt](llms.txt).

## Koin apps and runtime debugging

For work in Koin apps (not this plugin), point to the official Koin skills
([InsertKoinIO/koin-skills](https://github.com/InsertKoinIO/koin-skills)) and
the Kotzilla MCP (`https://mcp.kotzilla.io/mcp`) — it diagnoses `[KOIN-…]`
compile-safety errors and Koin runtime exceptions, and with the Kotzilla SDK
covers app behavior, performance and memory.
