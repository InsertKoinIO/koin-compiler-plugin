# Case Study: Now in Android Migration

Migration of the [Now in Android](https://github.com/android/nowinandroid) app from **Koin Annotations 2.3 (KSP)** to **Koin Compiler Plugin 0.2.4**.

## Overview

| Metric | Before (KSP) | After (Compiler Plugin) | Change |
|--------|--------------|------------------------|--------|
| **Lines changed** | - | - | **-546 lines** (760 added, 1306 removed) |
| **Files modified** | - | 44 files | Simplified |
| **Build config per module** | ~10 lines | 1 line | **-90%** |
| **KSP dependencies** | 5+ per module | 0 | Eliminated |
| **Kotlin version** | 2.2.20 | 2.3.20 | Upgraded |
| **Koin version** | 4.2.0-beta2 | 4.2.0 | Upgraded |

## Build Configuration Changes

### Before: Each Module with KSP (10+ lines)

```kotlin
// feature/bookmarks/build.gradle.kts
plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(libs.koin.annotations)
    ksp(libs.koin.ksp.compiler)
}

ksp {
    arg("KOIN_CONFIG_CHECK", "true")
}
```

### After: Convention Plugin (1 line)

```kotlin
// feature/bookmarks/build.gradle.kts
plugins {
    alias(libs.plugins.nowinandroid.koin)
}
```

### The Convention Plugin

Created a single reusable convention plugin for the entire project:

```kotlin
// build-logic/convention/src/main/kotlin/KoinConventionPlugin.kt
class KoinConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // Apply Koin Compiler Plugin (replaces KSP)
            pluginManager.apply("io.insert-koin.compiler.plugin")

            // Configure logging
            extensions.configure<KoinGradleExtension> {
                userLogs.set(true)
            }

            // Add koin-annotations dependency
            dependencies {
                add("implementation", libs.findLibrary("koin.annotations").get())
            }
        }
    }
}
```

## Dependency Changes

### libs.versions.toml

```diff
- koin-annotations = "2.3.1"
+ koinCompilerPlugin = "0.2.4"

- koin-annotations = {module = "io.insert-koin:koin-annotations", version.ref = "koin-annotations"}
- koin-ksp-compiler = {module = "io.insert-koin:koin-ksp-compiler", version.ref = "koin-annotations"}
+ koin-annotations = {group = "io.insert-koin", name = "koin-annotations", version.ref = "koin"}
+ koin-compiler-gradlePlugin = { group = "io.insert-koin", name = "koin-compiler-gradle-plugin", version.ref = "koinCompilerPlugin" }

+ [plugins]
+ koin-compiler = { id = "io.insert-koin.compiler.plugin", version.ref = "koinCompilerPlugin" }
```

**Key change:** `koin-annotations` version now follows `koin` version (4.2.0), not a separate version.

## Code Changes

### 1. ViewModel Annotation Import

All ViewModels updated from Android-specific to core annotation:

```diff
- import org.koin.android.annotation.KoinViewModel
+ import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class BookmarksViewModel(...)
```

**Files affected:** 7 ViewModels

### 2. Application Startup

```diff
// NiaApplication.kt
- import org.koin.ksp.generated.*
+ import org.koin.plugin.module.dsl.startKoin

override fun onCreate() {
-   startKoin {
+   startKoin<NiaApplication> {
        androidContext(this@NiaApplication)
        workManagerFactory()
    }
}
```

**Key change:** `startKoin<NiaApplication>` enables automatic `@Configuration` module discovery - no need to manually list modules.

### 3. Module Definitions (Unchanged)

The module definitions remain identical:

```kotlin
@Module(includes = [FeaturesModule::class, DomainModule::class])
@ComponentScan("com.google.samples.apps.nowinandroid.util", "com.google.samples.apps.nowinandroid.ui")
@Configuration
class AppModule {
    @KoinViewModel
    fun mainActivityViewModel(userDataRepository: UserDataRepository) =
        MainActivityViewModel(userDataRepository)
}

@Module
@ComponentScan("com.google.samples.apps.nowinandroid.feature")
class FeaturesModule

@Module
@ComponentScan("com.google.samples.apps.nowinandroid.core.domain")
class DomainModule
```

## Modules Migrated

| Module | KSP Config Removed | Notes |
|--------|-------------------|-------|
| `app` | ✅ | Main application module |
| `core/analytics` | ✅ | Firebase analytics |
| `core/common` | ✅ | Common utilities |
| `core/data` | ✅ | Data layer |
| `core/database` | ✅ | Room database |
| `core/datastore` | ✅ | DataStore preferences |
| `core/domain` | ✅ | Use cases |
| `core/network` | ✅ | Network layer |
| `core/notifications` | ✅ | Notifications |
| `core/testing` | ✅ | Test utilities |
| `feature/bookmarks` | ✅ | Bookmarks feature |
| `feature/foryou` | ✅ | For You feature |
| `feature/interests` | ✅ | Interests feature |
| `feature/search` | ✅ | Search feature |
| `feature/settings` | ✅ | Settings feature |
| `feature/topic` | ✅ | Topic feature |
| `sync/work` | ✅ | WorkManager sync |

**Total: 17 modules migrated**

## Benefits Observed

### 1. Simplified Build Configuration

**Before:** Each module needed:
- KSP plugin
- `koin-annotations` dependency
- `ksp(koin-ksp-compiler)` dependency
- `ksp { arg(...) }` configuration

**After:** Single line: `alias(libs.plugins.nowinandroid.koin)`

### 2. Faster Builds

- No separate KSP compilation task
- Integrated into Kotlin compilation
- Better incremental compilation support

### 3. Cleaner Dependencies

```diff
// Per module
- implementation(libs.koin.annotations)
- ksp(libs.koin.ksp.compiler)
+ // Auto-injected by compiler plugin
```

### 4. No Generated Files

- No `build/generated/ksp/` directories
- No `import org.koin.ksp.generated.*`
- Cleaner project structure

### 5. Automatic Module Discovery

With `@Configuration` annotation and `startKoin<App>()`:
- Modules are auto-discovered across the project
- No need to manually wire modules in Application class
- Cross-module discovery works automatically

## Migration Effort

| Task | Effort |
|------|--------|
| Create convention plugin | 15 minutes |
| Update libs.versions.toml | 5 minutes |
| Update module build.gradle.kts files | 20 minutes |
| Update ViewModel imports | 10 minutes |
| Update NiaApplication.kt | 5 minutes |
| Testing | 15 minutes |
| **Total** | **~1 hour** |

## Conclusion

The migration from Koin Annotations (KSP) to Koin Compiler Plugin resulted in:

1. **546 fewer lines of code** across the project
2. **90% reduction** in per-module build configuration
3. **Simplified dependency management** - no KSP dependencies
4. **Cleaner codebase** - no generated files to manage
5. **Better IDE support** - native Kotlin compiler integration
6. **Automatic module discovery** - via `@Configuration` and `startKoin<T>()`

The migration took approximately **1 hour** for a project with 17 modules, demonstrating the straightforward upgrade path from KSP to the compiler plugin.