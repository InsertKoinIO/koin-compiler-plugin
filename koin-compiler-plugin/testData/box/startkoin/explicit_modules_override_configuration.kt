// FILE: test.kt
// Regression for koin#2402: explicit @KoinApplication(modules = [...]) must load
// AFTER auto-discovered @Configuration modules so app-level overrides win at runtime.
//
// Pre-fix order:
//   1. Explicit App module registered Override
//   2. Discovered CoreConfiguration module registered Default — overwrote Override  ❌
//
// Post-fix order:
//   1. Discovered CoreConfiguration module registered Default
//   2. Explicit App module registered Override — overwrote Default  ✓
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton
import org.koin.plugin.module.dsl.startKoin

interface Feature {
    fun name(): String
}

// --- Discovered via @Configuration (simulates a dependency module) ---

class DefaultFeature : Feature {
    override fun name(): String = "default"
}

@Module
@Configuration
class CoreConfiguration {
    @Singleton
    fun defaultFeature(): Feature = DefaultFeature()
}

// --- Explicit via @KoinApplication(modules = [...]) (simulates the app) ---

class AppFeature : Feature {
    override fun name(): String = "app-override"
}

@Module
class AppModule {
    @Singleton
    fun appFeature(): Feature = AppFeature()
}

@KoinApplication(modules = [AppModule::class])
interface MyApp

fun box(): String {
    val koin = startKoin<MyApp> { }.koin
    val feature = koin.get<Feature>()
    // Explicit AppModule must be loaded AFTER the auto-discovered CoreConfiguration,
    // so its AppFeature binding wins under Koin's last-wins semantics.
    return if (feature.name() == "app-override") "OK"
    else "FAIL: expected app-override, got ${feature.name()}"
}
