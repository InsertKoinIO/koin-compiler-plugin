# Koin Compiler Plugin - Roadmap

Project status, completed work, and future plans.

## Completed

- [x] Implement reified type parameter syntax `single<T>()`, `factory<T>()`, etc.
- [x] Implement `Scope.create(::T)` constructor reference syntax
- [x] Implement `worker<T>()` DSL for Android WorkManager
- [x] Make `plugin-support` a full KMP library (all Koin targets, requires Koin 4.2.0-beta3+)
- [x] Create stub classes for ViewModel and ListenableWorker (avoid runtime dependencies)
- [ ] Gradle plugin auto-injects `koin-annotations` dependency (needs KMP support)
- [x] Move samples to separate folder for independent Kotlin version
- [x] Add Kotlin 2.2.x workarounds (stdlib for JS/WASM, disable metadata compilation)
- [x] Deprecate `org.koin.android.annotation.KoinViewModel`
- [x] Implement `@Configuration` cross-module discovery via hint functions
- [x] Fix KMP multi-phase FIR compilation (unique synthetic file names per class)
- [x] Fix expect/actual class handling (skip expect classes in FIR)
- [x] Fix K/Native synthetic file generation (skip on Native targets)
- [x] Fix cross-source-set module resolution (fallback via context.referenceFunctions)
- [x] Fix object module support (use irGetObject instead of constructor)
- [x] Add configurable logging (userLogs for component detection, debugLogs for internal processing)
- [x] Implement `koinConfiguration<T>()` DSL transformation
- [x] Implement `KoinApplication.withConfiguration<T>()` extension transformation
- [x] Implement `@PropertyValue` annotation for property defaults
- [x] Support definition annotations on top-level functions (discovered by `@ComponentScan`)

---

## Phase 1: Packaging & Publishing ✅

Prepared for public release on Maven Central.

### 1.1 Build Configuration
- [x] Review and finalize artifact coordinates
- [x] Ensure proper module structure for publishing
- [x] Add LICENSE file (Apache 2.0)
- [x] Add README.md with usage instructions

### 1.2 Maven Central Publishing
- [x] Configure `maven-publish` plugin for all modules
- [x] Set up GPG signing for artifacts
- [x] Configure Sonatype OSSRH credentials
- [x] Add POM metadata (description, license, developers, SCM URLs)
- [x] Publish artifacts: `compiler-plugin`, `gradle-plugin`
  - Note: `plugin-support` is included in Koin (`koin-annotations`), not published separately

### 1.3 Gradle Plugin Portal
- [x] Register plugin ID on Gradle Plugin Portal
- [x] Configure `java-gradle-plugin` publishing
- [x] Test plugin resolution via `plugins { id("io.insert-koin.compiler.plugin") }`

### 1.4 CI/CD & Release Process
- [x] Set up GitHub Actions for CI (build, test)
- [x] Automated release workflow (tag -> publish)
- [x] Version management strategy (semver)

### 1.5 Multi-Kotlin Version Strategy
- Deferred: Will follow latest Kotlin versions and adapt as needed
- No complex versioning scheme required for now

---

## Phase 2: Compile-Time Safety & Monitoring (Current)

Advanced features for production reliability.

### 2.1 `@PropertyValue` Default Values ✅
Support default values for property injection:
- [x] `@PropertyValue("key")` annotation on constant
- [x] Generate `getProperty("key", defaultValue)` calls

```kotlin
@PropertyValue("api.timeout")
val DEFAULT_TIMEOUT = 30000

@Factory
class ApiClient(@Property("api.timeout") val timeout: Int)
// Generates: factory { ApiClient(getProperty("api.timeout", DEFAULT_TIMEOUT)) }
```

### 2.2 JSR-330 Compatibility Toggle
Allow enabling/disabling JSR-330 annotation support via Gradle property:
- [ ] Add `jsr330` option to `koinCompiler { }` DSL (default: `true`)
- [ ] Pass option to compiler plugin via `CommandLineProcessor`
- [ ] Skip `jakarta.inject.*` and `javax.inject.*` annotation processing when disabled
- [ ] Document in README

```kotlin
// build.gradle.kts
koinCompiler {
    jsr330 = false  // Disable jakarta.inject/javax.inject support
}
```

### 2.3 Compile-Time Dependency Validation
Detect missing dependencies at compile time instead of runtime crashes.
- [ ] Implement two-pass approach: collect bindings during IR, validate graph at end
- [ ] Validate non-nullable parameters have definitions
- [ ] Validate `@Named` qualifiers match
- [ ] Validate scoped dependencies are in correct scope
- [ ] Report clear error messages with source locations
- [ ] Cross-module validation via hint functions
- See [COMPILE_TIME_SAFETY.md](COMPILE_TIME_SAFETY.md) for detailed design

### 2.4 `@Monitor` Annotation Support
Function interception for logging and performance capture:
- [ ] `@Monitor` annotation on class or function
- [ ] Generate wrapper code to intercept function calls
- [ ] Log function entry/exit with parameters
- [ ] Capture execution time and performance metrics
- [ ] Integration with Koin's logging/monitoring API

```kotlin
@Monitor
class MyService {
    fun fetchData(): Data { ... }  // Calls will be intercepted
}

// Or on specific functions
class MyService {
    @Monitor
    fun fetchData(): Data { ... }  // Only this function intercepted
}
```

### 2.5 Precompiled Module Index
Generate static index for faster startup:
- [ ] Generate `KoinModuleIndex` class listing all `@Configuration` modules
- [ ] Include module metadata (FQN, dependencies, provided types)
- [ ] Use index in `startKoin<T>()` instead of runtime discovery
- [ ] Benefits: Faster app startup, no reflection at runtime

```kotlin
// Generated by plugin
object KoinModuleIndex {
    val modules: List<ModuleInfo> = listOf(
        ModuleInfo("com.example.DataModule", provides = listOf("Repository", "Api")),
        ModuleInfo("com.example.AppModule", includes = listOf("DataModule"))
    )
}
```

---

## Multi-Kotlin Version Compatibility (Deferred)

**Current approach:** Follow latest Kotlin versions and adapt as needed. No complex versioning scheme required.

**Background:** Kotlin compiler plugins are not binary compatible across minor versions. A plugin compiled with Kotlin 2.2.21 won't work with Kotlin 2.3.x. If needed in the future, version-aligned releases (`0.1.0-kotlin-2.2.21`) can be implemented.