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

    // FQ names of types marked @Provided.
    //
    // Was a single global set shared by EVERY compilation in a daemon JVM. Thread-*safe* but not
    // compilation-*isolated*, which is a different property: `register()` / `isProvided()` /
    // `clear()` all run in the IR phase, so a parallel or interleaved compilation's `clear()`
    // (KoinAnnotationProcessor.collectAnnotations) could wipe another's registrations between its
    // register and its read. The @Provided type then looks unannotated and validation reports it
    // missing — a SILENT false positive on correctly-annotated code, with no hint that the
    // annotation was dropped.
    //
    // That matters more than it looks: @Provided is the required declaration for dependencies
    // arriving via `loadKoinModules(...)` (see docs/COMPILE_TIME_SAFETY.md), so an unreliable
    // registry makes the documented escape hatch unreliable too. Found by a full-suite run of
    // `entry_load_koin_modules_ok`, which passed in isolation and failed in the suite — the same
    // symptom and the same root cause as KTZ-4414 for @PropertyValue.
    //
    // Held per-thread via InheritableThreadLocal: IR fan-out threads inherit the same set
    // reference, so one compilation's threads share one set while distinct compilations stay
    // isolated. Mirrors [PropertyValueRegistry] and the per-thread collector/config in
    // KoinPluginLogger.
    private val providedTypesHolder: InheritableThreadLocal<MutableSet<String>> =
        object : InheritableThreadLocal<MutableSet<String>>() {
            override fun initialValue(): MutableSet<String> = ConcurrentHashMap.newKeySet()
        }

    private val providedTypes: MutableSet<String> get() = providedTypesHolder.get()

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
