package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fileOrNull
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
    val includedModules: List<IrClass>, // Classes from @Module(includes = [...])
    val createdAtStart: Boolean = false // `@Module(createdAtStart = true)` — eager-init all definitions in this module at startKoin
)

/**
 * Class-based definition (@Singleton class A, @Factory class B, etc.)
 */
data class DefinitionClass(
    val irClass: IrClass,
    val definitionType: DefinitionType,
    val packageFqName: FqName,
    val bindings: List<IrClass>, // Interfaces/superclasses to bind (auto-detected + explicit)
    val scopeClass: IrClass? = null, // Scope class from @Scope(MyScope::class) — typed scope
    val scopeName: String? = null, // Scope qualifier from @Scope(name = "session") — string-named scope
    val scopeArchetype: ScopeArchetype? = null, // Scope archetype (@ViewModelScope, etc.)
    val createdAtStart: Boolean = false, // createdAtStart parameter from @Single/@Singleton
    val qualifier: QualifierValue? = null // Qualifier from @Named/@Qualifier (propagated from cross-module hints)
)

/**
 * Function-based definition (inside @Module class)
 */
data class DefinitionFunction(
    val irFunction: IrSimpleFunction,
    val definitionType: DefinitionType,
    val returnTypeClass: IrClass,
    val bindings: List<IrClass> = emptyList(),
    val scopeClass: IrClass? = null,
    val scopeName: String? = null,
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
    val scopeName: String? = null,
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
    abstract val scopeClass: IrClass? // null = root scope (or scopeName/archetype) — typed scope
    abstract val scopeName: String? // null = no string-named scope — `@Scope(name = "...")`
    abstract val scopeArchetype: ScopeArchetype? // null = no archetype
    abstract val createdAtStart: Boolean

    // ── A3 durable-model carrier (PR1, purely additive) ──────────────────────────────────────
    // Requirements and source origin attached at COLLECTION time so the A3 verifier can read them
    // instead of re-deriving. See docs/COMPILE_SAFETY_A3_PLAN.md §4d.
    //
    // IMPORTANT: these are MUTABLE BODY properties with defaults, NOT primary-constructor
    // parameters. For a `data class` only primary-constructor properties participate in
    // equals/hashCode/copy, so keeping these in the (base) class body leaves all existing dedup
    // (`.distinctBy`, `definitionDedupeKey`, set membership, `putIfAbsent`) UNCHANGED. Do not
    // promote them into any subclass primary constructor.

    /** Constructor/function parameter requirements of this definition (empty until populated). */
    var requirements: List<Requirement> = emptyList()

    /** Where this definition was declared (module/file/line), or null when unrecoverable. */
    var origin: SourceOrigin? = null

    data class ClassDef(
        val irClass: IrClass,
        override val definitionType: DefinitionType,
        override val bindings: List<IrClass>,
        override val scopeClass: IrClass? = null,
        override val scopeName: String? = null,
        override val scopeArchetype: ScopeArchetype? = null,
        override val createdAtStart: Boolean = false,
        // Qualifier propagated from cross-module hint metadata. When non-null, overrides the
        // QualifierExtractor lookup on irClass — necessary for `@Qualifier` meta-annotations
        // that don't survive in cross-module Kotlin metadata.
        val qualifier: QualifierValue? = null
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
        override val scopeName: String? = null,
        override val scopeArchetype: ScopeArchetype? = null,
        override val createdAtStart: Boolean = false
    ) : Definition()

    data class TopLevelFunctionDef(
        val irFunction: IrSimpleFunction,
        override val definitionType: DefinitionType,
        override val returnTypeClass: IrClass,
        override val bindings: List<IrClass> = emptyList(),
        override val scopeClass: IrClass? = null,
        override val scopeName: String? = null,
        override val scopeArchetype: ScopeArchetype? = null,
        override val createdAtStart: Boolean = false
    ) : Definition()

    /**
     * DSL-based definition (single<T>(), factory<T>(), viewModel<T>(), etc.)
     * Collected during Phase 2 (KoinDSLTransformer) for inclusion in the safety graph.
     */
    data class DslDef(
        val irClass: IrClass,
        override val definitionType: DefinitionType,
        override val bindings: List<IrClass>,
        override val scopeClass: IrClass? = null,
        override val scopeName: String? = null,
        override val scopeArchetype: ScopeArchetype? = null,
        override val createdAtStart: Boolean = false,
        val modulePropertyId: String? = null,
        val providerOnly: Boolean = false,
        val qualifier: QualifierValue? = null, // Qualifier from @Named/@Qualifier on class or create(::function)
        // Source file containing the DSL call (`single<T>()`, `factory<T>()`, etc.). Always a file
        // in the current compile unit. Used as the stable anchor for synthetic hint files so
        // incremental compilation invalidates stale hints correctly (see issue #32). Null when
        // the registration site is unknown (e.g. discovered from a cross-module hint).
        val registrationSourceFile: IrFile? = null
    ) : Definition() {
        override val returnTypeClass: IrClass get() = irClass
    }

    /**
     * Provider-only definition discovered from cross-module function hints.
     * Represents a tagged top-level function (@Singleton fun provide...()) from another Gradle module.
     * Only contributes to the provided types set — its own requirements were validated in its source module.
     *
     * The [qualifier] is propagated from the hint function's encoded parameters (C2 metadata).
     */
    data class ExternalFunctionDef(
        override val definitionType: DefinitionType,
        override val returnTypeClass: IrClass,
        override val bindings: List<IrClass> = emptyList(),
        override val scopeClass: IrClass? = null,
        override val scopeName: String? = null,
        override val scopeArchetype: ScopeArchetype? = null,
        override val createdAtStart: Boolean = false,
        val qualifier: QualifierValue? = null
    ) : Definition()
}

enum class DefinitionType {
    SINGLE, FACTORY, SCOPED, VIEW_MODEL, WORKER
}

/**
 * Source origin of a definition — where the provider was declared. Attached at collection time so
 * the future A3 verifier can attribute a diagnostic to the culprit's own file/line rather than to
 * the aggregator (see docs/COMPILE_SAFETY_A3_PLAN.md §4d).
 *
 * Every field degrades to null when unavailable. Native/klib-declared symbols routinely lack a file
 * entry — nulls are expected and fine there.
 *
 * @param moduleFqName package FqName of the declaring file, used as a coarse origin locator. (The
 *   Gradle-module identity isn't available at the IR level; the package FqName is the closest stable
 *   proxy we have here.)
 * @param filePath source file path from the IR file entry, or null.
 * @param line 1-based line number of the declaration, or null.
 */
data class SourceOrigin(
    val moduleFqName: String?,
    val filePath: String?,
    val line: Int?,
) {
    companion object {
        /**
         * Best-effort origin for an IR declaration (class or function). Never throws — any missing
         * piece degrades to null. Safe on cross-module / native symbols that lack a file entry.
         */
        fun of(declaration: IrDeclaration): SourceOrigin {
            val file = declaration.fileOrNull
            val fileEntry = file?.fileEntry
            val startOffset = declaration.startOffset
            val line = if (fileEntry != null && startOffset >= 0) {
                runCatching { fileEntry.getLineNumber(startOffset) + 1 }.getOrNull()
            } else null
            return SourceOrigin(
                moduleFqName = file?.packageFqName?.asString(),
                filePath = fileEntry?.name,
                line = line,
            )
        }
    }
}

/**
 * Attach A3 durable-model metadata (origin + requirements) to a freshly-constructed [Definition] and
 * return the same instance for fluent use at collection sites. PR1, purely additive — no consumer
 * reads these yet. `requirements` is a lambda so callers that already have the backing IR compute it
 * only here. See docs/COMPILE_SAFETY_A3_PLAN.md §4d.
 */
internal inline fun <T : Definition> T.attachA3Metadata(
    declaration: IrDeclaration,
    requirements: () -> List<Requirement>,
): T {
    this.origin = SourceOrigin.of(declaration)
    this.requirements = requirements()
    return this
}

/**
 * Result of resolving definitions from a dependency JAR module.
 *
 * @param definitions The discovered definitions
 * @param isComplete Whether we could fully resolve all the module's definitions.
 *   - true: Module class resolved and definitions collected (including module-scan hints
 *     for @ComponentScan definitions).
 *   - false: Module class not on classpath (can't resolve ClassId at all).
 */
data class DependencyModuleResult(
    val definitions: List<Definition>,
    val isComplete: Boolean
)
