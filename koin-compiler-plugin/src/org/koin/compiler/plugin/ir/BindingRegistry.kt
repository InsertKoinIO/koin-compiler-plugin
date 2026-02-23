package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.koin.compiler.plugin.KoinPluginLogger
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
    val qualifier: QualifierValue?
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
 * A definition that provides a type to the DI container.
 */
data class ProvidedBinding(
    val typeKey: TypeKey,
    val qualifier: QualifierValue?,
    val scopeClass: IrClass?,
    val bindings: List<TypeKey>,
    val requirements: List<Requirement>,
    val sourceName: String
)

/**
 * Registry of all provided bindings, with per-module validation.
 *
 * Collects all definitions during annotation processing Phase 1,
 * then validates that each module's definitions can satisfy each other's requirements.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class BindingRegistry {

    private val allBindings = mutableListOf<ProvidedBinding>()

    fun clear() {
        allBindings.clear()
    }

    fun registerBinding(binding: ProvidedBinding) {
        allBindings.add(binding)
    }

    /**
     * Validate a module's definitions: check that all required dependencies are provided
     * within the set of definitions visible to this module.
     *
     * @param moduleName Name of the module (for error messages)
     * @param definitions All definitions collected for this module
     * @param parameterAnalyzer Analyzer for extracting parameter requirements
     * @param qualifierExtractor Extractor for reading qualifier annotations from definitions
     * @return Number of errors found
     */
    fun validateModule(
        moduleName: String,
        definitions: List<Definition>,
        parameterAnalyzer: ParameterAnalyzer,
        qualifierExtractor: QualifierExtractor
    ): Int {
        // Build the set of provided types for this module
        val providedTypes = mutableSetOf<ProviderKey>()

        for (def in definitions) {
            val typeKey = typeKeyFromDefinition(def)
            val qualifier = extractQualifierFromDefinition(def, qualifierExtractor)
            val scopeClass = def.scopeClass

            // The definition provides its own type
            providedTypes.add(ProviderKey(typeKey, qualifier, scopeClass))

            // It also provides its bound interfaces
            for (binding in def.bindings) {
                val bindingTypeKey = TypeKey(
                    classId = binding.fqNameWhenAvailable?.let { ClassId.topLevel(it) },
                    fqName = binding.fqNameWhenAvailable
                )
                providedTypes.add(ProviderKey(bindingTypeKey, qualifier, scopeClass))
            }
        }

        KoinPluginLogger.debug { "  Safety check for $moduleName: ${providedTypes.size} provided types, ${definitions.size} definitions" }

        // Validate each definition's requirements
        var errorCount = 0
        for (def in definitions) {
            val requirements = extractRequirements(def, parameterAnalyzer)
            val defName = definitionDisplayName(def)
            val defScopeClass = def.scopeClass

            for (req in requirements) {
                if (!req.requiresValidation()) continue

                // Check @Property separately
                if (req.isProperty && req.propertyKey != null) {
                    if (PropertyValueRegistry.getDefault(req.propertyKey) == null) {
                        KoinPluginLogger.debug {
                            "  @Property(\"${req.propertyKey}\") on $defName.${req.paramName} has no @PropertyValue default"
                        }
                    }
                    continue
                }

                // Look for a matching provider
                val found = findProvider(req, providedTypes, defScopeClass)
                if (!found) {
                    reportMissingDependency(req, defName, moduleName, providedTypes)
                    errorCount++
                }
            }
        }

        if (errorCount == 0) {
            KoinPluginLogger.debug { "  Safety check for $moduleName: OK (all dependencies satisfied)" }
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
            if (!qualifiersMatch(req.qualifier, provider.qualifier)) continue

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
        }

        return false
    }

    private fun qualifiersMatch(required: QualifierValue?, provided: QualifierValue?): Boolean {
        if (required == null && provided == null) return true
        if (required == null || provided == null) return required == null && provided == null
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
            classId = irClass.fqNameWhenAvailable?.let { ClassId.topLevel(it) },
            fqName = irClass.fqNameWhenAvailable
        )
    }

    private fun extractQualifierFromDefinition(def: Definition, qualifierExtractor: QualifierExtractor): QualifierValue? {
        // Extract qualifier from the IR element (class or function) using the shared extractor.
        return when (def) {
            is Definition.ClassDef -> qualifierExtractor.extractFromClass(def.irClass)
            is Definition.FunctionDef -> qualifierExtractor.extractFromDeclaration(def.irFunction)
            is Definition.TopLevelFunctionDef -> qualifierExtractor.extractFromDeclaration(def.irFunction)
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
