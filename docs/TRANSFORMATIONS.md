# Koin Compiler Plugin - Transformation Examples

Complete reference of all transformations performed by the plugin.

## Table of Contents

1. [DSL Syntax Transformations](#1-dsl-syntax-transformations)
2. [Annotation Transformations](#2-annotation-transformations)
3. [Parameter Handling](#3-parameter-handling)
4. [Module Injection](#4-module-injection)
5. [Scope Handling](#5-scope-handling)

---

## 1. DSL Syntax Transformations

### 1.1 Reified Type Parameter Syntax: `single<T>()`

**Input** (user code):
```kotlin
val myModule = module {
    single<MyService>()
}
```

**Output** (after transformation):
```kotlin
val myModule = module {
    buildSingle(MyService::class, null) { scope, params ->
        MyService(scope.get(), scope.getOrNull())
    }
}
```

**Processed by**: `KoinDSLTransformer.handleTypeParameterCall()`

### 1.2 All DSL Functions (Reified Type Syntax)

| Input | Output |
|-------|--------|
| `single<T>()` | `buildSingle(T::class, null) { scope, params -> T(...) }` |
| `factory<T>()` | `buildFactory(T::class, null) { scope, params -> T(...) }` |
| `viewModel<T>()` | `buildViewModel(T::class, null) { scope, params -> T(...) }` |
| `worker<T>()` | `buildWorker(T::class, null) { scope, params -> T(...) }` |
| `scoped<T>()` | `buildScoped(T::class, null) { scope, params -> T(...) }` |

**Note:** Constructor reference syntax `single(::T)` is NOT implemented. Use `single<T>()` instead.

### 1.3 Scope.create() (Constructor Reference)

**Input**:
```kotlin
val instance = koin.scope.create(::MyService)
```

**Output**:
```kotlin
val instance = MyService(koin.scope.get(), koin.scope.get())
```

**Processed by**: `KoinDSLTransformer.handleScopeCreate()`

This is the ONLY place where constructor reference syntax is supported.

---

## 2. Annotation Transformations

These transformations are handled by `KoinAnnotationProcessor` which fills the body of the FIR-generated `.module` property.

### 2.1 @Singleton / @Single

**Input**:
```kotlin
@Module @ComponentScan
class MyModule

@Singleton
class MyService(val repo: Repository)
```

**Generated** (fills FIR-generated property body):
```kotlin
val MyModule.module: Module get() = module {
    buildSingle(MyService::class, null) { scope, params ->
        MyService(scope.get())
    }
}
```

### 2.2 @Factory

**Input**:
```kotlin
@Factory
class MyService(val repo: Repository)
```

**Generated**:
```kotlin
buildFactory(MyService::class, null) { scope, params ->
    MyService(scope.get())
}
```

### 2.3 @KoinViewModel

**Input**:
```kotlin
@KoinViewModel
class MyViewModel(val repo: Repository) : ViewModel()
```

**Generated**:
```kotlin
buildViewModel(MyViewModel::class, null) { scope, params ->
    MyViewModel(scope.get())
}
```

### 2.4 @KoinWorker

**Input**:
```kotlin
@KoinWorker
class MyWorker(
    context: Context,
    params: WorkerParameters,
    val api: ApiService
) : CoroutineWorker(context, params)
```

**Generated**:
```kotlin
buildWorker(MyWorker::class, null) { scope, params ->
    MyWorker(scope.get(), scope.get(), scope.get())
}.bind(ListenableWorker::class)
```

### 2.5 @Scoped

**Input**:
```kotlin
@Scoped
class SessionData(val userId: String)
```

**Generated**:
```kotlin
buildScoped(SessionData::class, null) { scope, params ->
    SessionData(scope.get())
}
```

### 2.6 Top-Level Functions

Top-level functions can be annotated with definition annotations and discovered by `@ComponentScan`, just like annotated classes. Function parameters are used for dependency injection (similar to constructor parameters for classes).

**Input**:
```kotlin
package com.example

// Top-level functions with annotations
@Singleton
fun provideDatabase(): DatabaseService = PostgresDatabase()

@Factory
fun provideCache(db: DatabaseService): CacheService = RedisCache(db)

@Single
@Named("http")
fun provideHttpClient(): NetworkClient = OkHttpClient()

@Factory
fun provideServiceFacade(db: DatabaseService, cache: CacheService): ServiceFacade =
    ServiceFacade(db, cache)

// Module that scans the package
@Module
@ComponentScan("com.example")
class AppModule
```

**Generated** (fills FIR-generated property body):
```kotlin
val AppModule.module: Module get() = module {
    // From @Singleton fun provideDatabase()
    buildSingle(DatabaseService::class, null) { scope, params ->
        provideDatabase()
    }

    // From @Factory fun provideCache()
    buildFactory(CacheService::class, null) { scope, params ->
        provideCache(scope.get())
    }

    // From @Single @Named("http") fun provideHttpClient()
    buildSingle(NetworkClient::class, named("http")) { scope, params ->
        provideHttpClient()
    }

    // From @Factory fun provideServiceFacade()
    buildFactory(ServiceFacade::class, null) { scope, params ->
        provideServiceFacade(scope.get(), scope.get())
    }
}
```

**Key differences from class-based definitions:**
- Function return type determines the binding type (not class type)
- Function parameters are injected via `scope.get()` (like constructor parameters)
- No dispatch receiver needed (top-level functions are called directly)
- All parameter annotations work: `@Named`, `@InjectedParam`, `@Property`, nullable, `Lazy<T>`, `List<T>`

**With parameter annotations**:
```kotlin
@Factory
fun provideApiClient(
    @Named("prod") baseUrl: String,
    @InjectedParam userId: String,
    @Property("api.timeout") timeout: Int,
    cache: CacheService?  // nullable = getOrNull()
): ApiClient = ApiClient(baseUrl, userId, timeout, cache)
```

**Generated**:
```kotlin
buildFactory(ApiClient::class, null) { scope, params ->
    provideApiClient(
        scope.get(named("prod")),
        params.get(),
        scope.getProperty("api.timeout"),
        scope.getOrNull()
    )
}
```

---

## 3. Parameter Handling

These parameter transformations apply to BOTH DSL syntax (`single<T>()`) and annotation syntax (`@Singleton`).

### 3.1 @Named on Class

**Input**:
```kotlin
@Singleton
@Named("production")
class ProductionService : Service
```

**Output**:
```kotlin
buildSingle(ProductionService::class, named("production")) { scope, params ->
    ProductionService()
}.bind(Service::class)
```

### 3.2 @Named on Parameter

**Input**:
```kotlin
@Singleton
class Consumer(@Named("production") val service: Service)
```

**Output**:
```kotlin
buildSingle(Consumer::class, null) { scope, params ->
    Consumer(scope.get(named("production")))
}
```

### 3.3 @Qualifier with Type

Type-based qualifiers use a class reference instead of a string.

**Input (on class)**:
```kotlin
@Singleton
@Qualifier(ProductionConfig::class)
class ProductionService : Service
```

**Output**:
```kotlin
buildSingle(ProductionService::class, typeQualifier<ProductionConfig>()) { scope, params ->
    ProductionService()
}.bind(Service::class)
```

**Input (on parameter)**:
```kotlin
@Singleton
class Consumer(@Qualifier(ProductionConfig::class) val service: Service)
```

**Output**:
```kotlin
buildSingle(Consumer::class, null) { scope, params ->
    Consumer(scope.get(typeQualifier<ProductionConfig>()))
}
```

### 3.4 @InjectedParam

**Input**:
```kotlin
@Factory
class MyClass(@InjectedParam val id: Int, val service: Service)
```

**Output**:
```kotlin
buildFactory(MyClass::class, null) { scope, params ->
    MyClass(params.get(), scope.get())
}
```

**Usage**: `koin.get<MyClass> { parametersOf(42) }`

### 3.4 @Property

**Input**:
```kotlin
@Singleton
class Config(
    @Property("server.url") val serverUrl: String,
    @Property("server.port") @PropertyValue("8080") val port: String
)
```

**Output**:
```kotlin
buildSingle(Config::class, null) { scope, params ->
    Config(
        scope.getProperty("server.url"),
        scope.getProperty("server.port", "8080")
    )
}
```

### 3.5 @ScopeId

**Input**:
```kotlin
@Factory
class ProfileService(@ScopeId(name = "user_session") val session: UserSession)
```

**Output**:
```kotlin
buildFactory(ProfileService::class, null) { scope, params ->
    ProfileService(scope.getScope("user_session").get())
}
```

### 3.6 Scope Parameter

**Input**:
```kotlin
@Scoped
class ScopedService(val scope: Scope)
```

**Output**:
```kotlin
buildScoped(ScopedService::class, null) { scope, params ->
    ScopedService(scope)  // passes the scope receiver directly
}
```

### 3.7 Nullable Parameters

**Input**:
```kotlin
@Singleton
class MyService(val required: A, val optional: B? = null)
```

**Output**:
```kotlin
buildSingle(MyService::class, null) { scope, params ->
    MyService(scope.get(), scope.getOrNull())
}
```

### 3.6 Lazy Parameters

**Input**:
```kotlin
@Singleton
class MyService(val lazyDep: Lazy<HeavyService>)
```

**Output**:
```kotlin
buildSingle(MyService::class, null) { scope, params ->
    MyService(scope.inject())
}
```

### 3.7 List Parameters

**Input**:
```kotlin
@Singleton
class Aggregator(val handlers: List<Handler>)
```

**Output**:
```kotlin
buildSingle(Aggregator::class, null) { scope, params ->
    Aggregator(scope.getAll())
}
```

### 3.8 Default Value Handling

When `skipDefaultValues` is enabled (default: `true`), parameters with Kotlin default values skip DI injection and use the default value instead. This only applies to non-nullable parameters without explicit annotations.

**Input**:
```kotlin
class ServiceWithDefault(val a: A, val name: String = "default", val count: Int = 42)
single<ServiceWithDefault>()
```

**Output** (with `skipDefaultValues = true`):
```kotlin
buildSingle(ServiceWithDefault::class, null) { scope, params ->
    ServiceWithDefault(scope.get())  // name and count use Kotlin defaults
}
```

**Output** (with `skipDefaultValues = false`):
```kotlin
buildSingle(ServiceWithDefault::class, null) { scope, params ->
    ServiceWithDefault(scope.get(), scope.get(), scope.get())  // all params injected
}
```

**Rules**:
- Non-nullable + default value + no annotation = **skip injection** (use default)
- Nullable + default value = **still inject** via `getOrNull()`
- Annotated + default value (`@Named`, `@Qualifier`, etc.) = **still inject**

### 3.9 Complete Parameter Decision Table

| Parameter Type | Annotation | Default Value | Generated Call |
|----------------|------------|:---:|----------------|
| `T` (non-nullable) | - | No | `scope.get()` |
| `T` (non-nullable) | - | Yes | *(skipped - uses Kotlin default)* |
| `T?` (nullable) | - | No | `scope.getOrNull()` |
| `T?` (nullable) | - | Yes | `scope.getOrNull()` |
| `T` | `@Named("x")` | No | `scope.get(named("x"))` |
| `T` | `@Named("x")` | Yes | `scope.get(named("x"))` |
| `T?` | `@Named("x")` | No | `scope.getOrNull(named("x"))` |
| `T` | `@Qualifier(X::class)` | No | `scope.get(typeQualifier<X>())` |
| `T?` | `@Qualifier(X::class)` | No | `scope.getOrNull(typeQualifier<X>())` |
| `T` | `@InjectedParam` | No | `params.get()` |
| `T?` | `@InjectedParam` | No | `params.getOrNull()` |
| `String` | `@Property("key")` | No | `scope.getProperty("key")` |
| `String` | `@Property("key") @PropertyValue("default")` | No | `scope.getProperty("key", "default")` |
| `Lazy<T>` | - | No | `scope.inject()` |
| `Lazy<T>` | `@Named("x")` | No | `scope.inject(named("x"))` |
| `Lazy<T>` | `@Qualifier(X::class)` | No | `scope.inject(typeQualifier<X>())` |
| `List<T>` | - | No | `scope.getAll()` |
| `T` | `@ScopeId(name = "x")` | No | `scope.getScope("x").get()` |
| `T` | `@ScopeId(X::class)` | No | `scope.getScope("fqName").get()` |
| `T` | `@Provided` | No | `scope.get()` *(validation skipped)* |
| `Scope` | *(auto-detected)* | No | `scope` *(receiver passed directly)* |

> **Note**: The "Default Value = Yes, skipped" behavior requires `skipDefaultValues = true` (the default). When disabled, all parameters are injected regardless of default values.

---

## 4. Module Injection

### 4.1 startKoin<T>()

**Input**:
```kotlin
@KoinApplication(modules = [MyModule::class, OtherModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {
        printLogger()
    }
}
```

**Output**:
```kotlin
fun main() {
    startKoinWith(listOf(MyModule().module, OtherModule().module)) {
        printLogger()
    }
}
```

**Processed by**: `KoinStartTransformer`

### 4.2 koinApplication<T>()

**Input**:
```kotlin
@KoinApplication(modules = [MyModule::class])
object MyApp

val koin = koinApplication<MyApp> {
    printLogger()
}.koin
```

**Output**:
```kotlin
val koin = koinApplicationWith(listOf(MyModule().module)) {
    printLogger()
}.koin
```

### 4.3 koinConfiguration<T>()

**Input**:
```kotlin
@KoinApplication(modules = [MyModule::class])
object MyApp

val config = koinConfiguration<MyApp>()
```

**Output**:
```kotlin
val config = koinConfigurationWith(listOf(MyModule().module))
```

**Usage**: Use with `includes()` to add configuration to a `koinApplication`:
```kotlin
val koin = koinApplication {
    includes(koinConfiguration<MyApp>())
}.koin
```

### 4.4 KoinApplication.withConfiguration<T>()

**Input**:
```kotlin
@KoinApplication(modules = [MyModule::class])
object MyApp

val koin = koinApplication {
    printLogger()
    withConfiguration<MyApp>()
}.koin
```

**Output**:
```kotlin
val koin = koinApplication {
    printLogger()
    withConfigurationWith(listOf(MyModule().module))
}.koin
```

**Note**: `withConfiguration<T>()` is an extension on `KoinApplication` that modifies the application in place.

### 4.5 Auto-Discovery (@Configuration)

**Input**:
```kotlin
@Module @ComponentScan @Configuration
class FeatureModule

@KoinApplication  // No explicit modules
object MyApp

startKoin<MyApp>()
```

**Expected Output** (same compilation unit only):
```kotlin
startKoinWith(listOf(FeatureModule().module))
```

**Note**: Cross-module discovery is limited. Use explicit `modules = [...]` for reliability.

### 4.6 Configuration Labels

Labels allow filtering which `@Configuration` modules are discovered:

**Input**:
```kotlin
// Module with default label
@Module @ComponentScan @Configuration
class ProdModule

// Module with specific label
@Module @ComponentScan @Configuration("test")
class TestModule

// Module with multiple labels
@Module @ComponentScan @Configuration("test", "prod")
class SharedModule

// App requesting specific labels
@KoinApplication(configurations = ["test"])
object TestApp
```

**Result**: `startKoin<TestApp>()` discovers `TestModule` and `SharedModule` (both have "test" label).

### 4.7 JSR-330 Support

The plugin supports JSR-330 (Jakarta/Javax) annotations as alternatives to Koin annotations:

**Input**:
```kotlin
import jakarta.inject.Singleton
import jakarta.inject.Inject
import jakarta.inject.Named

@Singleton
class MySingleton

@Inject  // Equivalent to @Factory
class MyInjectable(val dep: Dependency)

class Consumer(@Named("prod") val service: Service)
```

**Output**:
```kotlin
// @Singleton → buildSingle
buildSingle(MySingleton::class, null) { scope, params ->
    MySingleton()
}

// @Inject → buildFactory
buildFactory(MyInjectable::class, null) { scope, params ->
    MyInjectable(scope.get())
}
```

**Supported JSR-330 annotations**:
| JSR-330 Annotation | Koin Equivalent |
|--------------------|-----------------|
| `jakarta.inject.Singleton` | `@Single` |
| `javax.inject.Singleton` | `@Single` |
| `jakarta.inject.Inject` | `@Factory` |
| `javax.inject.Inject` | `@Factory` |
| `jakarta.inject.Named` | `@Named` |
| `javax.inject.Named` | `@Named` |

---

## 5. Scope Handling

### 5.1 @Scope on Class

**Input**:
```kotlin
class MyScope

@Scoped
@Scope(MyScope::class)
class SessionData
```

**Generated** (in module body):
```kotlin
scope<MyScope> {
    buildScoped(SessionData::class, null) { scope, params ->
        SessionData()
    }
}
```

### 5.2 Scope Archetypes

**Input**:
```kotlin
@KoinViewModel
@ViewModelScope
class MyViewModel

@Scoped
@ActivityScope
class ActivityData

@Scoped
@FragmentScope
class FragmentData
```

**Generated**: Each uses the appropriate scope builder from Koin.

### 5.3 ScopeDSL Functions

All DSL functions also work inside `scope { }` blocks:

**Input**:
```kotlin
val myModule = module {
    scope<MyScope> {
        scoped<SessionData>()
        factory<SessionHandler>()
        viewModel<ScopedViewModel>()
    }
}
```

**Output**:
```kotlin
val myModule = module {
    scope<MyScope> {
        buildScoped(SessionData::class, null) { ... }
        buildFactory(SessionHandler::class, null) { ... }
        buildViewModel(ScopedViewModel::class, null) { ... }
    }
}
```

---

## Appendix: Annotation Quick Reference

### Definition Annotations

| Annotation | Generated DSL | Applies To |
|------------|---------------|------------|
| `@Single` / `@Singleton` | `buildSingle { }` | Classes, module functions, top-level functions |
| `@Factory` | `buildFactory { }` | Classes, module functions, top-level functions |
| `@Scoped` | `buildScoped { }` | Classes, module functions, top-level functions |
| `@KoinViewModel` | `buildViewModel { }` | Classes, module functions, top-level functions |
| `@KoinWorker` | `buildWorker { }` | Classes, module functions, top-level functions |

### Qualifier Annotations

| Annotation | Effect |
|------------|--------|
| `@Named("x")` on class | String qualifier for definition → `named("x")` |
| `@Named("x")` on parameter | `get(named("x"))` |
| `@Qualifier(name = "x")` | String qualifier → `named("x")` |
| `@Qualifier(MyType::class)` | Type qualifier → `typeQualifier<MyType>()` |
| Custom `@Qualifier` annotation | Uses annotation name as qualifier |

### Parameter Annotations

| Annotation | Effect |
|------------|--------|
| `@InjectedParam` | Use `params.get()` |
| `@Property("key")` | Use `getProperty("key")` |
| `@PropertyValue("default")` | Default for property |

### Module Annotations

| Annotation | Effect |
|------------|--------|
| `@Module` | Marks class as module container |
| `@ComponentScan` | Scans package for annotated classes and top-level functions |
| `@Configuration` | Tags module for auto-discovery (default label) |
| `@Configuration("label1", "label2")` | Tags module with specific labels for filtered discovery |
| `@KoinApplication(modules = [...])` | Specifies modules to inject |
| `@KoinApplication(configurations = ["label"])` | Discovers modules with matching configuration labels |

### JSR-330 Annotations

| Annotation | Koin Equivalent |
|------------|-----------------|
| `jakarta.inject.Singleton` / `javax.inject.Singleton` | `@Single` |
| `jakarta.inject.Inject` / `javax.inject.Inject` | `@Factory` |
| `jakarta.inject.Named` / `javax.inject.Named` | `@Named` |
