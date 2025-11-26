package org.koin.compiler.plugin

import org.jetbrains.kotlin.ir.declarations.IrProperty

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

    // Map from property key to the IrProperty that provides the default value
    private val propertyDefaults = mutableMapOf<String, IrProperty>()

    /**
     * Register a property default value.
     */
    fun register(propertyKey: String, property: IrProperty) {
        propertyDefaults[propertyKey] = property
        KoinPluginLogger.debug { "  Registered @PropertyValue(\"$propertyKey\") -> ${property.name}" }
    }

    /**
     * Get the property that provides the default value for a given key.
     */
    fun getDefault(propertyKey: String): IrProperty? {
        return propertyDefaults[propertyKey]
    }

    /**
     * Check if a default value exists for a property key.
     */
    fun hasDefault(propertyKey: String): Boolean {
        return propertyDefaults.containsKey(propertyKey)
    }

    /**
     * Clear the registry (called between compilation units if needed).
     */
    fun clear() {
        propertyDefaults.clear()
    }
}