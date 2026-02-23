package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.name.FqName

/**
 * Data model classes for Koin annotation processing.
 *
 * These represent the intermediate data collected during Phase 1 (annotation collection)
 * and consumed during Phase 2 (code generation) of the annotation processor.
 */

/**
 * A class annotated with @Module, possibly with @ComponentScan.
 */
data class ModuleClass(
    val irClass: IrClass,
    val hasComponentScan: Boolean, // Whether @ComponentScan is present (enables package scanning)
    val scanPackages: List<String>, // Packages to scan (empty = current package if hasComponentScan)
    val definitionFunctions: List<DefinitionFunction>, // Functions inside @Module with definition annotations
    val includedModules: List<IrClass> // Classes from @Module(includes = [...])
)

/**
 * Class-based definition (@Singleton class A, @Factory class B, etc.)
 */
data class DefinitionClass(
    val irClass: IrClass,
    val definitionType: DefinitionType,
    val packageFqName: FqName,
    val bindings: List<IrClass>, // Interfaces/superclasses to bind (auto-detected + explicit)
    val scopeClass: IrClass? = null, // Scope class from @Scope(MyScope::class)
    val scopeArchetype: ScopeArchetype? = null, // Scope archetype (@ViewModelScope, etc.)
    val createdAtStart: Boolean = false // createdAtStart parameter from @Single/@Singleton
)

/**
 * Function-based definition (inside @Module class)
 */
data class DefinitionFunction(
    val irFunction: IrSimpleFunction,
    val definitionType: DefinitionType,
    val returnTypeClass: IrClass,
    val scopeClass: IrClass? = null,
    val scopeArchetype: ScopeArchetype? = null,
    val createdAtStart: Boolean = false
)

/**
 * Top-level function definition (@Singleton fun provide...(), @Factory fun create...())
 */
data class DefinitionTopLevelFunction(
    val irFunction: IrSimpleFunction,
    val definitionType: DefinitionType,
    val packageFqName: FqName,
    val returnTypeClass: IrClass,
    val bindings: List<IrClass> = emptyList(),
    val scopeClass: IrClass? = null,
    val scopeArchetype: ScopeArchetype? = null,
    val createdAtStart: Boolean = false
)

/**
 * Unified definition abstraction used during code generation.
 * Wraps class-based, function-based, and top-level function-based definitions.
 */
sealed class Definition {
    abstract val definitionType: DefinitionType
    abstract val returnTypeClass: IrClass
    abstract val bindings: List<IrClass>
    abstract val scopeClass: IrClass? // null = root scope
    abstract val scopeArchetype: ScopeArchetype? // null = no archetype
    abstract val createdAtStart: Boolean

    data class ClassDef(
        val irClass: IrClass,
        override val definitionType: DefinitionType,
        override val bindings: List<IrClass>,
        override val scopeClass: IrClass? = null,
        override val scopeArchetype: ScopeArchetype? = null,
        override val createdAtStart: Boolean = false
    ) : Definition() {
        override val returnTypeClass: IrClass get() = irClass
    }

    data class FunctionDef(
        val irFunction: IrSimpleFunction,
        val moduleInstance: IrClass,
        override val definitionType: DefinitionType,
        override val returnTypeClass: IrClass,
        override val bindings: List<IrClass> = emptyList(),
        override val scopeClass: IrClass? = null,
        override val scopeArchetype: ScopeArchetype? = null,
        override val createdAtStart: Boolean = false
    ) : Definition()

    data class TopLevelFunctionDef(
        val irFunction: IrSimpleFunction,
        override val definitionType: DefinitionType,
        override val returnTypeClass: IrClass,
        override val bindings: List<IrClass> = emptyList(),
        override val scopeClass: IrClass? = null,
        override val scopeArchetype: ScopeArchetype? = null,
        override val createdAtStart: Boolean = false
    ) : Definition()
}

enum class DefinitionType {
    SINGLE, FACTORY, SCOPED, VIEW_MODEL, WORKER
}
