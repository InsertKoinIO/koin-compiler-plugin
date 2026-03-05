# Koin Compiler Plugin

A native Kotlin Compiler Plugin for [Koin](https://insert-koin.io/) dependency injection. Write `single<T>()` instead of `singleOf(::T)`, use `@Singleton`/`@Factory` annotations - all resolved at compile-time by the Kotlin compiler, no KSP required.

## Setup

```kotlin
// build.gradle.kts
plugins {
    id("io.insert-koin.compiler.plugin") version "0.4.0-beta16"
}

dependencies {
    implementation("io.insert-koin:koin-core:4.2.0")
    // If using annotations
    implementation("io.insert-koin:koin-annotations:4.2.0")
}
```

## Usage

### DSL Syntax

> **Important**: Use imports from `org.koin.plugin.module.dsl`, not the classic Koin DSL.

```kotlin
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.viewModel
import org.koin.plugin.module.dsl.worker

val myModule = module {
    single<MyService>()
    factory<MyRepository>()
    viewModel<MyViewModel>()
    worker<MyWorker>()        // Android WorkManager
}
```

### Annotation Syntax

```kotlin
@Module
@ComponentScan
class AppModule

@Singleton
class MyService(val repo: Repository)

@Factory
class MyRepository

@KoinViewModel
class MyViewModel(val service: MyService) : ViewModel()
```

### Module Injection

```kotlin
@KoinApplication(modules = [AppModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {
        printLogger()
    }
}
```

## Features

- **DSL Transformation**: `single<T>()`, `factory<T>()`, `viewModel<T>()`, `worker<T>()`, `scoped<T>()`
- **Annotation Processing**: `@Singleton`, `@Factory`, `@KoinViewModel`, `@Scoped`, `@KoinWorker`
- **Auto Module Injection**: `@KoinApplication(modules = [...])` with `startKoin<App>()`
- **Full KMP Support**: JVM, JS, WASM, iOS, macOS, watchOS, tvOS, Linux, Windows
- **JSR-330 Support**: `@Inject`, `@Named` from `javax.inject` or `jakarta.inject`

## Configuration

```kotlin
// build.gradle.kts
koinCompiler {
    userLogs = true           // Log component detection
    debugLogs = true          // Log internal processing (verbose)
    dslSafetyChecks = true    // Validates create() is the only instruction in lambda (default: true)
    skipDefaultValues = true  // Skip injection for parameters with default values (default: true)
}
```

## Compatibility

- **Koin**: 4.2.0-RC1+
- **Kotlin**: K2 compiler required (2.3.x+)

## Documentation

See the [docs/](docs/) folder:

- [MIGRATION_FROM_KSP.md](docs/MIGRATION_FROM_KSP.md) - Migration from Koin Annotations (KSP)
- [CASE_STUDY_NOW_IN_ANDROID.md](docs/CASE_STUDY_NOW_IN_ANDROID.md) - Real-world migration case study
- [DEBUGGING.md](docs/DEBUGGING.md) - Debugging guide and common issues
- [ARCHITECTURE.md](docs/ARCHITECTURE.md) - Project structure and compilation flow
- [TRANSFORMATIONS.md](docs/TRANSFORMATIONS.md) - All transformation examples
- [ROADMAP.md](docs/ROADMAP.md) - Project status and future plans

## Development

```bash
# Build and install to Maven Local
./install.sh

# Run tests
./gradlew :koin-compiler-plugin:test

# Run sample (from test-apps/)
cd test-apps && ./gradlew :sample-app:jvmRun
```

## License

Apache 2.0