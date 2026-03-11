package org.koin.compiler.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for types marked with @Provided.
 *
 * Types annotated with @Provided are considered externally available at runtime
 * (e.g., Android framework types like Context, Activity, SavedStateHandle).
 * They are skipped during compile-time safety validation to avoid false positives.
 *
 * Usage:
 * ```kotlin
 * @Provided
 * class Context  // external type, always available at runtime
 *
 * @Singleton
 * class MyService(val ctx: Context)  // no safety error — Context is @Provided
 * ```
 */
object ProvidedTypeRegistry {

    // Set of FQ names of types marked @Provided (thread-safe for Gradle daemon parallel builds)
    private val providedTypes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Register a type as @Provided.
     */
    fun register(fqName: String) {
        providedTypes.add(fqName)
        KoinPluginLogger.debug { "  Registered @Provided type: $fqName" }
    }

    /**
     * Check if a type is marked @Provided.
     */
    fun isProvided(fqName: String): Boolean {
        return fqName in providedTypes
    }

    /**
     * Clear the registry (called between compilation units if needed).
     */
    fun clear() {
        providedTypes.clear()
    }
}
