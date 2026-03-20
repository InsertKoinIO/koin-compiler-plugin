# Migration Guide: Koin Annotations (KSP) to Koin Compiler Plugin

This guide helps you migrate from **Koin Annotations 2.x** (using KSP) to the **Koin Compiler Plugin**.

## Overview

| | Koin Annotations (KSP) | Koin Compiler Plugin |
|---|------------------------|---------------------|
| **Processing** | KSP code generation | Kotlin compiler plugin (FIR + IR) |
| **Generated files** | `*Module.kt` files in `build/generated` | Inline transformation, no generated files |
| **Kotlin version** | K1 and K2 | K2 only (2.3.x+) |
| **Koin version** | 3.x, 4.x | 4.2.0-RC1+ |

## Step 1: Update Build Configuration

### Before (KSP) - JVM/Android Only

```kotlin
// build.gradle.kts
plugins {
    id("com.google.devtools.ksp") version "..."
}

dependencies {
    implementation("io.insert-koin:koin-core:...")
    implementation("io.insert-koin:koin-annotations:...")
    ksp("io.insert-koin:koin-ksp-compiler:...")
}
```

### Before (KSP) - KMP Projects (Complex!)

For Kotlin Multiplatform, KSP requires significant boilerplate:

```kotlin
// build.gradle.kts - KMP with KSP
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core)
            implementation(libs.koin.annotations)
        }
    }

    // KSP: Manual source set configuration for generated files
    sourceSets.named("commonMain").configure {
        kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
    }
}

// KSP: Must add compiler to EVERY target separately
dependencies {
    add("kspCommonMainMetadata", libs.koin.ksp.compiler)
    add("kspAndroid", libs.koin.ksp.compiler)
    add("kspIosX64", libs.koin.ksp.compiler)
    add("kspIosArm64", libs.koin.ksp.compiler)
    add("kspIosSimulatorArm64", libs.koin.ksp.compiler)
}

// KSP: Task dependency hack for metadata compilation
tasks.matching { it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata" }.configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
}

ksp {
    arg("KOIN_CONFIG_CHECK", "true")
}
```

**That's ~25 lines of KSP boilerplate for KMP!**

### After (Compiler Plugin)

```kotlin
// build.gradle.kts
plugins {
    id("io.insert-koin.compiler.plugin") version "0.4.2"
}

dependencies {
    implementation("io.insert-koin:koin-core:4.2.0-RC1")
    implementation("io.insert-koin:koin-annotations:4.2.0-RC1")
}
```

**Remove:**
- KSP plugin (`com.google.devtools.ksp`)
- `ksp("io.insert-koin:koin-ksp-compiler:...")` dependency

**Keep:**
- `koin-annotations` dependency (required for annotations)

## Step 2: Annotation Changes

Most annotations work identically. Here are the differences:

### Unchanged Annotations

These work exactly the same:

| Annotation | Status |
|------------|--------|
| `@Module` | ✅ Same |
| `@ComponentScan` | ✅ Same |
| `@Single` / `@Singleton` | ✅ Same |
| `@Factory` | ✅ Same |
| `@Scoped` | ✅ Same |
| `@KoinViewModel` | ✅ Same |
| `@Named("...")` | ✅ Same |
| `@InjectedParam` | ✅ Same |
| `@Property("...")` | ✅ Same |
| `@Scope(T::class)` | ✅ Same |

### Package Changes

```kotlin
// Before (KSP) - Android ViewModel annotation
import org.koin.android.annotation.KoinViewModel

// After (Compiler Plugin) - use core annotation
import org.koin.core.annotation.KoinViewModel
```

### `@KoinWorker` for WorkManager

```kotlin
// Before (KSP) - may not have been supported
// After (Compiler Plugin) - fully supported
@KoinWorker
class MyWorker(
    context: Context,
    params: WorkerParameters,
    val api: ApiService
) : CoroutineWorker(context, params)
```

### Top-Level Function Definitions (New)

The compiler plugin supports definition annotations on top-level functions, discovered by `@ComponentScan`:

```kotlin
// Top-level functions with annotations
@Singleton
fun provideDatabase(): DatabaseService = PostgresDatabase()

@Factory
fun provideCache(db: DatabaseService): CacheService = RedisCache(db)

@Single
@Named("http")
fun provideHttpClient(): NetworkClient = OkHttpClient()

// Module that scans the package
@Module
@ComponentScan("com.example")
class AppModule
```

- Function return type determines the binding type
- Function parameters are injected as dependencies (like constructor parameters)
- All parameter annotations work: `@Named`, `@InjectedParam`, `@Property`, nullable, `Lazy<T>`, `List<T>`

## Step 3: Module Definition Changes

### Generated Module Access

```kotlin
// Before (KSP) - reference generated module class
import org.koin.ksp.generated.module

startKoin {
    modules(AppModule().module)
}

// After (Compiler Plugin) - same syntax, but module is extension property
startKoin {
    modules(AppModule().module)
}
```

### Using `@KoinApplication` (New)

The compiler plugin supports automatic module injection:

```kotlin
// Define app with modules
@KoinApplication(modules = [AppModule::class, NetworkModule::class])
object MyApp

// Modules are auto-injected
fun main() {
    startKoin<MyApp> {
        printLogger()
    }
}
```

## Step 4: DSL Syntax Changes

The compiler plugin introduces a cleaner DSL syntax:

### Before (KSP style)

```kotlin
val myModule = module {
    singleOf(::MyService)
    factoryOf(::MyRepository)
    viewModelOf(::MyViewModel)
}
```

### After (Compiler Plugin)

```kotlin
val myModule = module {
    single<MyService>()
    factory<MyRepository>()
    viewModel<MyViewModel>()
}
```

Both styles define the same dependencies, but the compiler plugin uses reified type parameters instead of constructor references.

### Comparison Table

| KSP Style | Compiler Plugin Style |
|-----------|----------------------|
| `singleOf(::MyService)` | `single<MyService>()` |
| `factoryOf(::MyRepo)` | `factory<MyRepo>()` |
| `viewModelOf(::MyVM)` | `viewModel<MyVM>()` |
| `scopedOf(::MyScoped)` | `scoped<MyScoped>()` |
| `workerOf(::MyWorker)` | `worker<MyWorker>()` |

### With Qualifiers

```kotlin
// Before
singleOf(::MyService) { named("production") }

// After
single<MyService>(named("production"))
```

### With Options

```kotlin
// Before
singleOf(::MyService) {
    named("production")
    bind<Service>()
    createdAtStart()
}

// After
single<MyService>(named("production")).withOptions {
    bind<Service>()
    createdAtStart()
}
```

## Step 5: Remove Generated Files

After migration, you can delete:

1. **Generated source directories**: `build/generated/ksp/`
2. **Any manual imports** from `org.koin.ksp.generated`

The compiler plugin doesn't generate visible files - transformations happen inline during compilation.

## Step 6: Multi-Module Projects

### Before (KSP)

Each module needed KSP configuration:

```kotlin
// feature/build.gradle.kts
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("io.insert-koin:koin-annotations:1.3.1")
    ksp("io.insert-koin:koin-ksp-compiler:1.3.1")
}
```

### After (Compiler Plugin)

Just apply the plugin:

```kotlin
// feature/build.gradle.kts
plugins {
    id("io.insert-koin.compiler.plugin") version "0.4.2"
}
```

### Cross-Module Discovery

Use `@Configuration` for automatic module discovery across compilation units:

```kotlin
// In feature module
@Module
@ComponentScan
@Configuration
class FeatureModule

// In app module - FeatureModule is auto-discovered
@KoinApplication
object MyApp

startKoin<MyApp>()  // FeatureModule automatically included
```

## Step 7: KMP Migration

The compiler plugin has full KMP support:

```kotlin
// commonMain
@Module
@ComponentScan
class CommonModule {
    @Singleton
    fun provideRepository(): Repository = RepositoryImpl()
}

// Works on all targets: JVM, JS, WASM, iOS, macOS, Linux, Windows
```

### Expect/Actual Classes

```kotlin
// commonMain
@Module
expect class PlatformModule

// androidMain
@Module
actual class PlatformModule {
    @Singleton
    fun providePlatform(): Platform = AndroidPlatform()
}

// iosMain
@Module
actual class PlatformModule {
    @Singleton
    fun providePlatform(): Platform = IosPlatform()
}
```

## Troubleshooting

### "Unresolved reference: module"

Make sure you're using Koin 4.2.0-RC1 or later and that you have the `koin-annotations` dependency. The `module` extension property is provided by `koin-annotations`.

### Compile errors with K1

The compiler plugin requires K2 (Kotlin 2.3.x+). If you're on an older Kotlin version, you'll need to either:
1. Upgrade to Kotlin 2.3.x+
2. Stay on Koin Annotations with KSP

### Missing dependencies at runtime

Enable logging to debug:

```kotlin
koinCompiler {
    userLogs = true   // See what's being processed
    debugLogs = true  // Detailed internal logging
}
```

## Benefits of Migration

| Feature | KSP | Compiler Plugin |
|---------|-----|-----------------|
| Build speed | Separate KSP task | Integrated in compilation |
| Generated files | Visible in build/ | None (inline) |
| IDE support | Depends on KSP plugin | Native Kotlin support |
| Incremental builds | KSP incremental | Kotlin incremental |
| KMP support | Limited | Full (all targets) |
| Compile-time safety | Partial | Full constructor validation |
| Top-level functions | Not supported | Fully supported |

## Quick Migration Checklist

- [ ] Update Kotlin to 2.3.x+
- [ ] Update Koin to 4.2.0-RC1+
- [ ] Remove KSP plugin from build.gradle.kts
- [ ] Remove `koin-ksp-compiler` dependency (keep `koin-annotations`)
- [ ] Add `io.insert-koin.compiler.plugin` plugin
- [ ] Update `@KoinViewModel` import to `org.koin.core.annotation`
- [ ] Delete `build/generated/ksp/` directory
- [ ] Test and verify all dependencies resolve correctly