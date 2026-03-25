package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.ProvidedTypeRegistry
import org.koin.compiler.plugin.PropertyValueRegistry

/**
 * Identifies a provided type in the DI container.
 *
 * @param classId The ClassId of the type (for serializable cross-module comparisons)
 * @param fqName The FqName (for display in error messages)
 */
data class TypeKey(
    val classId: ClassId?,
    val fqName: FqName?
) {
    fun render(): String = fqName?.asString() ?: classId?.asFqNameString() ?: "<unknown>"
}

/**
 * A dependency requirement from a constructor/function parameter.
 */
data class Requirement(
    val typeKey: TypeKey,
    val paramName: String,
    val isNullable: Boolean,
    val hasDefault: Boolean,
    val isInjectedParam: Boolean,
    val isLazy: Boolean,
    val isList: Boolean,
    val isProperty: Boolean,
    val propertyKey: String?,
    val qualifier: QualifierValue?,
    val isProvided: Boolean = false
) {
    /**
     * Whether this requirement must be validated (must have a matching provider).
     * Returns false for requirements that are safe without a provider.
     */
    fun requiresValidation(): Boolean {
        if (isInjectedParam) return false  // Provided at runtime via parametersOf()
        if (isNullable) return false        // getOrNull() handles missing
        if (isList) return false            // getAll() returns empty if none
        if (isProperty) return false        // Property injection (validated separately)

        // If skipDefaultValues is enabled and param has a default, skip
        if (KoinPluginLogger.skipDefaultValuesEnabled && hasDefault && qualifier == null) return false

        return true
    }
}

/**
 * Registry of all provided bindings, with per-module validation.
 *
 * Collects all definitions during annotation processing Phase 1,
 * then validates that each module's definitions can satisfy each other's requirements.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class BindingRegistry {

    companion object {
        /**
         * Framework types that are always available at runtime (provided by the platform, not DI).
         * These are skipped during validation to avoid false positives.
         */
        private val WHITELISTED_TYPES = setOf(
            // Android core
            "android.content.Context",
            "android.app.Activity",
            "android.app.Application",
            // AndroidX - scope-provided components
            "androidx.activity.ComponentActivity",
            "androidx.fragment.app.Fragment",
            "androidx.lifecycle.SavedStateHandle",
            "androidx.work.WorkerParameters",
        )

        fun isWhitelistedType(fqName: String): Boolean = fqName in WHITELISTED_TYPES
    }

    /**
     * Validate a module's definitions: check that all required dependencies are provided
     * within the set of definitions visible to this module.
     *
     * @param moduleName Name of the module (for error messages)
     * @param definitions All definitions collected for this module (used to build provided types)
     * @param parameterAnalyzer Analyzer for extracting parameter requirements
     * @param qualifierExtractor Extractor for reading qualifier annotations from definitions
     * @param definitionsToValidate Subset of definitions whose requirements should be checked.
     *   If null, all definitions are validated. Use this to skip re-validating definitions
     *   that were already checked at A2 while still including them as providers.
     * @return Number of errors found
     */
    fun validateModule(
        moduleName: String,
        definitions: List<Definition>,
        parameterAnalyzer: ParameterAnalyzer,
        qualifierExtractor: QualifierExtractor,
        definitionsToValidate: List<Definition>? = null
    ): Int {
        // Build the set of provided types from ALL definitions
        val providedTypes = mutableSetOf<ProviderKey>()

        for (def in definitions) {
            val typeKey = typeKeyFromDefinition(def)
            val qualifier = extractQualifierFromDefinition(def, qualifierExtractor)
            val scopeClass = def.scopeClass

            // The definition provides its own type
            providedTypes.add(ProviderKey(typeKey, qualifier, scopeClass))
            val scopeStr = scopeClass?.fqNameWhenAvailable?.asString()?.let { " (scope=$it)" } ?: ""
            val qualifierStr = when (qualifier) {
                is QualifierValue.StringQualifier -> " @Named(\"${qualifier.name}\")"
                is QualifierValue.TypeQualifier -> " @Qualifier(${qualifier.irClass.name}::class)"
                null -> ""
            }
            KoinPluginLogger.debug { "    provides: ${typeKey.render()}$qualifierStr$scopeStr" }

            // It also provides its bound interfaces
            for (binding in def.bindings) {
                val bindingTypeKey = TypeKey(
                    classId = ParameterAnalyzer.classIdFromIrClass(binding),
                    fqName = binding.fqNameWhenAvailable
                )
                providedTypes.add(ProviderKey(bindingTypeKey, qualifier, scopeClass))
                KoinPluginLogger.debug { "    provides (binding): ${bindingTypeKey.render()}$qualifierStr$scopeStr" }
            }
        }

        // Only validate requirements from the specified subset (or all if not specified)
        val toValidate = definitionsToValidate ?: definitions

        KoinPluginLogger.debug { "  provided types registry: ${providedTypes.size} entries" }
        KoinPluginLogger.debug { "  definitions to check: ${toValidate.size}/${definitions.size}" }

        // Validate each definition's requirements
        var errorCount = 0
        for (def in toValidate) {
            val requirements = extractRequirements(def, parameterAnalyzer)
            val defName = definitionDisplayName(def)
            val defScopeClass = def.scopeClass
            KoinPluginLogger.debug { "    validating: $defName (${requirements.size} requirements)" }

            for (req in requirements) {
                if (!req.requiresValidation()) {
                    val reason = when {
                        req.isInjectedParam -> "@InjectedParam"
                        req.isNullable -> "nullable"
                        req.isList -> "List (getAll)"
                        req.isProperty -> "@Property"
                        KoinPluginLogger.skipDefaultValuesEnabled && req.hasDefault && req.qualifier == null -> "hasDefault (skipDefaultValues)"
                        else -> "unknown"
                    }
                    KoinPluginLogger.debug { "      skip '${req.paramName}': ${req.typeKey.render()} ($reason)" }
                    continue
                }

                // Skip @Provided types and framework-provided types (always available at runtime)
                val reqFqName = req.typeKey.fqName?.asString() ?: req.typeKey.classId?.asFqNameString()
                if (req.isProvided || (reqFqName != null && ProvidedTypeRegistry.isProvided(reqFqName))) {
                    KoinPluginLogger.debug { "      skip '${req.paramName}': ${req.typeKey.render()} (@Provided)" }
                    continue
                }
                if (reqFqName != null && isWhitelistedType(reqFqName)) {
                    KoinPluginLogger.debug { "      skip '${req.paramName}': ${req.typeKey.render()} (framework whitelist)" }
                    continue
                }

                // Look for a matching provider
                val found = findProvider(req, providedTypes, defScopeClass)
                if (found) {
                    KoinPluginLogger.debug { "      OK '${req.paramName}': ${req.typeKey.render()}" }
                } else {
                    KoinPluginLogger.debug { "      MISSING '${req.paramName}': ${req.typeKey.render()}" }
                    reportMissingDependency(req, defName, moduleName, providedTypes)
                    errorCount++
                }
            }
        }

        if (errorCount == 0) {
            KoinPluginLogger.debug { "  result: OK - all dependencies satisfied for $moduleName" }
        } else {
            KoinPluginLogger.debug { "  result: FAILED - $errorCount missing dependencies in $moduleName" }
        }

        return errorCount
    }

    /**
     * Search for a provider matching the requirement.
     * Checks both same-scope and root-scope providers.
     */
    private fun findProvider(
        req: Requirement,
        providedTypes: Set<ProviderKey>,
        consumerScopeClass: IrClass?
    ): Boolean {
        val reqFqName = req.typeKey.fqName
        val reqClassId = req.typeKey.classId

        for (provider in providedTypes) {
            // Type must match (by FqName or ClassId)
            val typeMatch = when {
                reqFqName != null && provider.typeKey.fqName != null -> reqFqName == provider.typeKey.fqName
                reqClassId != null && provider.typeKey.classId != null -> reqClassId == provider.typeKey.classId
                else -> false
            }
            if (!typeMatch) continue

            // Qualifier must match
            if (!qualifiersMatch(req.qualifier, provider.qualifier)) {
                KoinPluginLogger.debug { "        type match ${req.typeKey.render()} but qualifier mismatch: required=${req.qualifier?.debugString()} vs provided=${provider.qualifier?.debugString()}" }
                continue
            }

            // Scope visibility: root-scope providers are visible everywhere,
            // same-scope providers are visible within the scope
            val providerScope = provider.scopeClass
            if (providerScope == null) {
                // Root scope — visible to all
                return true
            }
            if (consumerScopeClass != null && providerScope.fqNameWhenAvailable == consumerScopeClass.fqNameWhenAvailable) {
                // Same scope
                return true
            }
            // Different scope — not visible, keep searching
            KoinPluginLogger.debug { "        type match ${req.typeKey.render()} but scope mismatch: consumer=${consumerScopeClass?.fqNameWhenAvailable} vs provider=${providerScope.fqNameWhenAvailable}" }
        }

        return false
    }

    private fun qualifiersMatch(required: QualifierValue?, provided: QualifierValue?): Boolean {
        if (required == null && provided == null) return true
        if (required == null || provided == null) return false
        return when {
            required is QualifierValue.StringQualifier && provided is QualifierValue.StringQualifier ->
                required.name == provided.name
            required is QualifierValue.TypeQualifier && provided is QualifierValue.TypeQualifier ->
                required.irClass.fqNameWhenAvailable == provided.irClass.fqNameWhenAvailable
            else -> false
        }
    }

    private fun reportMissingDependency(
        req: Requirement,
        defName: String,
        moduleName: String,
        providedTypes: Set<ProviderKey>
    ) {
        val typeName = req.typeKey.render()
        val qualifierStr = when (val q = req.qualifier) {
            is QualifierValue.StringQualifier -> " qualified with @Named(\"${q.name}\")"
            is QualifierValue.TypeQualifier -> " qualified with @Qualifier(${q.irClass.name}::class)"
            null -> ""
        }

        val message = buildString {
            append("Missing dependency: $typeName$qualifierStr")
            append("\n  required by: $defName (parameter '${req.paramName}')")
            append("\n  in module: $moduleName")

            // Hint: find similar bindings (same type, different qualifier)
            val similarBindings = providedTypes.filter { provider ->
                val typeMatch = when {
                    req.typeKey.fqName != null && provider.typeKey.fqName != null ->
                        req.typeKey.fqName == provider.typeKey.fqName
                    req.typeKey.classId != null && provider.typeKey.classId != null ->
                        req.typeKey.classId == provider.typeKey.classId
                    else -> false
                }
                typeMatch && !qualifiersMatch(req.qualifier, provider.qualifier)
            }
            if (similarBindings.isNotEmpty()) {
                append("\n  Hint: Found similar binding: $typeName")
                val first = similarBindings.first()
                when (val q = first.qualifier) {
                    is QualifierValue.StringQualifier -> append(" with qualifier @Named(\"${q.name}\")")
                    is QualifierValue.TypeQualifier -> append(" with qualifier @Qualifier(${q.irClass.name}::class)")
                    null -> append(" (no qualifier)")
                }
            }
        }

        KoinPluginLogger.error(message)
    }

    // ================================================================================
    // Helpers
    // ================================================================================

    private fun typeKeyFromDefinition(def: Definition): TypeKey {
        val irClass = def.returnTypeClass
        return TypeKey(
            classId = ParameterAnalyzer.classIdFromIrClass(irClass),
            fqName = irClass.fqNameWhenAvailable
        )
    }

    private fun extractQualifierFromDefinition(def: Definition, qualifierExtractor: QualifierExtractor): QualifierValue? {
        // Extract qualifier from the IR element (class or function) using the shared extractor.
        return when (def) {
            is Definition.ClassDef -> qualifierExtractor.extractFromClass(def.irClass)
            is Definition.FunctionDef -> qualifierExtractor.extractFromDeclaration(def.irFunction)
            is Definition.TopLevelFunctionDef -> qualifierExtractor.extractFromDeclaration(def.irFunction)
            is Definition.DslDef -> qualifierExtractor.extractFromClass(def.irClass)
            is Definition.ExternalFunctionDef -> def.qualifier
        }
    }

    private fun extractRequirements(def: Definition, analyzer: ParameterAnalyzer): List<Requirement> {
        return when (def) {
            is Definition.ClassDef -> {
                val constructor = findConstructorToUse(def.irClass)
                if (constructor != null) analyzer.analyzeConstructor(constructor) else emptyList()
            }
            is Definition.FunctionDef -> analyzer.analyzeFunction(def.irFunction)
            is Definition.TopLevelFunctionDef -> analyzer.analyzeFunction(def.irFunction)
            is Definition.DslDef -> {
                val constructor = findConstructorToUse(def.irClass)
                if (constructor != null) analyzer.analyzeConstructor(constructor) else emptyList()
            }
            is Definition.ExternalFunctionDef -> emptyList() // Provider-only, requirements validated in source module
        }
    }

    /**
     * Find the constructor to use for injection.
     * Prefers @Inject annotated constructor, otherwise uses primary constructor.
     */
    private fun findConstructorToUse(targetClass: IrClass): org.jetbrains.kotlin.ir.declarations.IrConstructor? {
        val injectConstructor = targetClass.declarations
            .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrConstructor>()
            .firstOrNull { constructor ->
                constructor.annotations.any { annotation ->
                    val fqName = annotation.type.classFqName?.asString()
                    fqName == "jakarta.inject.Inject" || fqName == "javax.inject.Inject"
                }
            }
        return injectConstructor ?: targetClass.primaryConstructor
    }

    private fun definitionDisplayName(def: Definition): String {
        return when (def) {
            is Definition.ClassDef -> def.irClass.fqNameWhenAvailable?.asString() ?: def.irClass.name.asString()
            is Definition.FunctionDef -> "${def.moduleInstance.name}.${def.irFunction.name}()"
            is Definition.TopLevelFunctionDef -> def.irFunction.fqNameWhenAvailable?.asString()
                ?: def.irFunction.name.asString()
            is Definition.DslDef -> "dsl:${def.irClass.fqNameWhenAvailable?.asString() ?: def.irClass.name.asString()}"
            is Definition.ExternalFunctionDef -> def.returnTypeClass.fqNameWhenAvailable?.asString()
                ?: def.returnTypeClass.name.asString()
        }
    }

    /**
     * Key for tracking what's provided.
     */
    internal data class ProviderKey(
        val typeKey: TypeKey,
        val qualifier: QualifierValue?,
        val scopeClass: IrClass?
    ) {
        /** Scope FqName for comparison (null = root scope). */
        val scopeFqName: String? get() = scopeClass?.fqNameWhenAvailable?.asString()
    }

    // ================================================================================
    // Unit-testable validation (no IR dependencies)
    // ================================================================================

    /**
     * Validate requirements against a provided set using only data types.
     * Used by unit tests to verify matching logic without IR.
     *
     * @param requirements List of (defName, scopeFqName, requirement) triples
     * @param provided Set of (TypeKey, qualifier, scopeFqName) triples representing providers
     * @param moduleName For error messages
     * @return List of (defName, requirement) pairs that are missing
     */
    fun validateRequirementsData(
        requirements: List<Triple<String, String?, Requirement>>,
        provided: Set<Triple<TypeKey, QualifierValue?, String?>>,
        moduleName: String = "TestModule"
    ): List<Pair<String, Requirement>> {
        val missing = mutableListOf<Pair<String, Requirement>>()

        for ((defName, consumerScopeFqName, req) in requirements) {
            if (!req.requiresValidation()) continue
            if (req.isProperty) continue

            // Skip @Provided types and framework-provided types (same as real validation path)
            val reqFqName = req.typeKey.fqName?.asString() ?: req.typeKey.classId?.asFqNameString()
            if (req.isProvided || (reqFqName != null && ProvidedTypeRegistry.isProvided(reqFqName))) continue
            if (reqFqName != null && isWhitelistedType(reqFqName)) continue

            val found = findProviderData(req, provided, consumerScopeFqName)
            if (!found) {
                missing.add(defName to req)
            }
        }

        return missing
    }

    /**
     * Search for a provider matching the requirement using plain data.
     */
    internal fun findProviderData(
        req: Requirement,
        provided: Set<Triple<TypeKey, QualifierValue?, String?>>,
        consumerScopeFqName: String?
    ): Boolean {
        val reqFqName = req.typeKey.fqName
        val reqClassId = req.typeKey.classId

        for ((providerTypeKey, providerQualifier, providerScopeFqName) in provided) {
            val typeMatch = when {
                reqFqName != null && providerTypeKey.fqName != null -> reqFqName == providerTypeKey.fqName
                reqClassId != null && providerTypeKey.classId != null -> reqClassId == providerTypeKey.classId
                else -> false
            }
            if (!typeMatch) continue

            if (!qualifiersMatch(req.qualifier, providerQualifier)) continue

            // Scope visibility
            if (providerScopeFqName == null) return true  // Root scope visible to all
            if (consumerScopeFqName != null && providerScopeFqName == consumerScopeFqName) return true
        }

        return false
    }

    internal fun qualifiersMatchPublic(a: QualifierValue?, b: QualifierValue?): Boolean = qualifiersMatch(a, b)
}
