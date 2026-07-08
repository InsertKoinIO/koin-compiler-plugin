package org.koin.compiler.plugin

import org.jetbrains.kotlin.ir.declarations.IrProperty
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for @PropertyValue annotated properties.
 *
 * Stores the mapping from property key to the IrProperty that provides the default value.
 *
 * Usage:
 * ```kotlin
 * @PropertyValue("name")
 * val defaultName = "MyName"
 *
 * @Factory
 * class MyClass(@Property("name") val name: String)
 * ```
 *
 * Will generate: `factory { MyClass(getProperty("name", defaultName)) }`
 */
object PropertyValueRegistry {

    // Map from property key to the IrProperty that provides the default value.
    //
    // Was a single global ConcurrentHashMap shared by EVERY compilation in a daemon JVM. It's
    // thread-*safe* but not compilation-*isolated*: `register()`/`getDefault()`/`clear()` all run
    // in the IR phase (KoinAnnotationProcessor + KoinArgumentGenerator), so a parallel or
    // interleaved compilation's `clear()` could wipe another's registration between its register
    // and its read — dropping the @PropertyValue default (`getProperty(key, default)` degrades to
    // `getProperty(key)`). In the shared-JVM test suite that made `property_value_ok` order-flaky
    // (KTZ-4414). Held per-thread (InheritableThreadLocal — IR fan-out threads inherit the same
    // map reference, so one compilation's threads share one map; distinct compilations are
    // isolated). Mirrors the per-thread treatment of the collector/config in KoinPluginLogger.
    private val propertyDefaults: InheritableThreadLocal<ConcurrentHashMap<String, IrProperty>> =
        object : InheritableThreadLocal<ConcurrentHashMap<String, IrProperty>>() {
            override fun initialValue(): ConcurrentHashMap<String, IrProperty> = ConcurrentHashMap()
        }

    private val defaults: ConcurrentHashMap<String, IrProperty>
        get() = propertyDefaults.get()

    /**
     * Register a property default value.
     */
    fun register(propertyKey: String, property: IrProperty) {
        defaults[propertyKey] = property
        KoinPluginLogger.debug { "  Registered @PropertyValue(\"$propertyKey\") -> ${property.name}" }
    }

    /**
     * Get the property that provides the default value for a given key.
     */
    fun getDefault(propertyKey: String): IrProperty? {
        return defaults[propertyKey]
    }

    /**
     * Check if a default value exists for a property key.
     */
    fun hasDefault(propertyKey: String): Boolean {
        return defaults.containsKey(propertyKey)
    }

    /**
     * Clear the current compilation's registry (called at the start of annotation processing).
     */
    fun clear() {
        defaults.clear()
    }
}
