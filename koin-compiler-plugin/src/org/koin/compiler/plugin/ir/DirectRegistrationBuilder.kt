package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irTrue
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrVarargElement
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.isJs
import org.jetbrains.kotlin.platform.isWasm
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.platform.konan.isNative
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Emits a definition's registration directly against the Koin registry instead of through the
 * runtime DSL (KTZ-4829).
 *
 * Legacy shape, per definition, at module load:
 *
 *     buildSingle(T::class, qualifier, { T(get()) }).bind(I::class)
 *
 * which allocates a [KoinDefinition] wrapper, builds the index key String with `indexKey()` for the
 * primary type, and — per `bind` — re-indexes every secondary type bound so far (k binds → k(k+1)/2
 * key builds). Direct shape:
 *
 *     val def = BeanDefinition(scopeQualifier, T::class, qualifier, { T(get()) }, Kind.Singleton, listOf(I::class), null)
 *     val factory = SingleInstanceFactory(def)
 *     module.saveMapping("pkg.T:q:_root_", factory)
 *     module.saveMapping("pkg.I:q:_root_", factory)
 *
 * The key is `"<class full name>:<qualifier value>:<scope qualifier>"`, exactly what
 * `org.koin.core.definition.indexKey` builds, with the class name computed the way
 * `KoinPlatformTools.getClassName` does on the target platform: `KClass.java.name` on JVM,
 * `qualifiedName` on Native, `simpleName` on JS/Wasm.
 *
 * Everything referenced here is part of koin-core's binary surface since 4.2.0 (`Module.saveMapping`
 * is `@PublishedApi`), so the emitted code runs against the whole supported Koin range.
 *
 * Fallback rule — silent is worse than broken: when any part of the key cannot be derived with
 * certainty (local class, `kotlin.*`/`java.*` type whose JVM name is mapped, unknown platform,
 * archetype scope, missing runtime symbol), [canEmit] returns false and the caller emits the legacy
 * DSL call for that definition. With `verifyGeneratedKeys` every emitted key is additionally checked
 * against `indexKey()` at module load; box tests run with it on.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class DirectRegistrationBuilder(
    private val context: IrPluginContext,
    private val qualifierExtractor: QualifierExtractor,
) {

    sealed class ScopeSpec {
        object Root : ScopeSpec()
        data class Typed(val scopeClass: IrClass) : ScopeSpec()
        data class Named(val name: String) : ScopeSpec()
    }

    class Spec(
        val targetType: IrClass,
        val qualifier: QualifierValue?,
        val bindings: List<IrClass>,
        val definitionType: DefinitionType,
        val createdAtStart: Boolean,
        val scope: ScopeSpec,
    )

    private val koinDefinitionPackage = FqName("org.koin.core.definition")
    private val koinInstancePackage = FqName("org.koin.core.instance")
    private val kotlinCollections = FqName("kotlin.collections")

    private val kClassClass by lazy { context.referenceClass(ClassId.topLevel(KoinAnnotationFqNames.KCLASS))?.owner }
    private val beanDefinitionClass by lazy { referenceClass(koinDefinitionPackage, "BeanDefinition") }
    private val kindClass by lazy { referenceClass(koinDefinitionPackage, "Kind") }
    private val moduleClass by lazy { referenceClass(FqName("org.koin.core.module"), "Module") }
    private val scopeDslClass by lazy { referenceClass(FqName("org.koin.dsl"), "ScopeDSL") }
    private val scopeRegistryCompanion by lazy { referenceClass(FqName("org.koin.core.registry"), "ScopeRegistry")?.companionObject() }

    // The satellite DSLs are `createDefinition(kind = Factory).bind(X::class)`: viewModel binds
    // androidx.lifecycle.ViewModel, worker binds androidx.work.ListenableWorker. Direct registration
    // mirrors them as a Factory with that one extra secondary type.
    private val viewModelClass by lazy { referenceClass(FqName("androidx.lifecycle"), "ViewModel") }
    private val listenableWorkerClass by lazy { referenceClass(FqName("androidx.work"), "ListenableWorker") }

    // KoinDefinition(module, factory): what the DSL path returns so user chains (bind, binds,
    // withOptions, onClose) still have their receiver. One small object; no key String.
    private val koinDefinitionClass by lazy { referenceClass(koinDefinitionPackage, "KoinDefinition") }
    private val koinDefinitionConstructor by lazy { koinDefinitionClass?.constructors?.firstOrNull { it.regularParameters.size == 2 } }

    // BeanDefinition(scopeQualifier, primaryType, qualifier, definition, kind, secondaryTypes, allowOverride)
    private val beanDefinitionConstructor by lazy { beanDefinitionClass?.constructors?.firstOrNull { it.regularParameters.size == 7 } }
    private val singleFactoryConstructor by lazy { referenceClass(koinInstancePackage, "SingleInstanceFactory")?.constructors?.firstOrNull { it.regularParameters.size == 1 } }
    private val factoryFactoryConstructor by lazy { referenceClass(koinInstancePackage, "FactoryInstanceFactory")?.constructors?.firstOrNull { it.regularParameters.size == 1 } }
    private val scopedFactoryConstructor by lazy { referenceClass(koinInstancePackage, "ScopedInstanceFactory")?.constructors?.firstOrNull { it.regularParameters.size == 2 } }

    private val saveMapping by lazy { moduleClass?.simpleFunction("saveMapping") { it.regularParameters.size == 2 } }
    private val prepareForCreationAtStart by lazy { moduleClass?.simpleFunction("prepareForCreationAtStart") { it.regularParameters.size == 1 } }
    private val scopeDslModuleGetter by lazy { scopeDslClass?.propertyGetter("module") }
    private val scopeDslQualifierGetter by lazy { scopeDslClass?.propertyGetter("scopeQualifier") }
    private val rootScopeQualifierGetter by lazy { scopeRegistryCompanion?.propertyGetter("rootScopeQualifier") }
    private val definitionQualifierGetter by lazy { beanDefinitionClass?.propertyGetter("qualifier") }
    private val definitionScopeQualifierGetter by lazy { beanDefinitionClass?.propertyGetter("scopeQualifier") }

    private val emptyListFunction by lazy { topLevelFunction(kotlinCollections, "emptyList") { it.regularParameters.isEmpty() } }
    private val listOfSingle by lazy { topLevelFunction(kotlinCollections, "listOf") { it.regularParameters.size == 1 && it.regularParameters[0].varargElementType == null } }
    private val listOfVararg by lazy { topLevelFunction(kotlinCollections, "listOf") { it.regularParameters.size == 1 && it.regularParameters[0].varargElementType != null } }
    private val indexKeyFunction by lazy { topLevelFunction(koinDefinitionPackage, "indexKey") { it.regularParameters.size == 3 } }
    private val errorFunction by lazy { topLevelFunction(FqName("kotlin"), "error") { it.regularParameters.size == 1 } }

    /**
     * Whether [spec] can be registered directly with a key we are certain about. Any doubt → false,
     * and the caller keeps the legacy DSL call for this definition.
     */
    fun canEmit(spec: Spec, returnDefinition: Boolean = false): Boolean {
        if (!KoinPluginLogger.directRegistrationEnabled) return false
        if (returnDefinition && koinDefinitionConstructor == null) return skip(spec, "KoinDefinition constructor not found")
        val factoryConstructor = when (spec.definitionType) {
            DefinitionType.SINGLE -> singleFactoryConstructor
            DefinitionType.FACTORY, DefinitionType.VIEW_MODEL, DefinitionType.WORKER -> factoryFactoryConstructor
            DefinitionType.SCOPED -> scopedFactoryConstructor
        }
        if (factoryConstructor == null) return skip(spec, "InstanceFactory constructor not found")
        // The caller has already resolved the satellite DSL function (and reported the missing
        // artifact otherwise); the binding class must additionally be on the classpath.
        if (implicitBinding(spec.definitionType) == null && spec.definitionType in setOf(DefinitionType.VIEW_MODEL, DefinitionType.WORKER)) {
            return skip(spec, "satellite binding class (ViewModel / ListenableWorker) not found")
        }
        if (kClassClass == null || beanDefinitionConstructor == null || kindClass == null || saveMapping == null) return skip(spec, "koin-core registration symbols not found")
        if (spec.createdAtStart && prepareForCreationAtStart == null) return skip(spec, "prepareForCreationAtStart not found")
        if (platformClassName(spec.targetType) == null) return skip(spec, "no compile-time class name for ${spec.targetType.fqNameWhenAvailable}")
        if (allBindings(spec).any { platformClassName(it) == null }) return skip(spec, "no compile-time class name for a bound type")
        if (qualifierKey(spec.qualifier) == null) return skip(spec, "no compile-time value for the qualifier")
        if (scopeKey(spec.scope) == null) return skip(spec, "no compile-time value for the scope qualifier")
        when (spec.scope) {
            ScopeSpec.Root -> if (rootScopeQualifierGetter == null) return skip(spec, "rootScopeQualifier not found")
            is ScopeSpec.Typed, is ScopeSpec.Named -> if (scopeDslModuleGetter == null || scopeDslQualifierGetter == null) return skip(spec, "ScopeDSL accessors not found")
        }
        val bindingCount = allBindings(spec).size
        if (bindingCount == 0 && emptyListFunction == null) return skip(spec, "emptyList not found")
        if (bindingCount == 1 && listOfSingle == null) return skip(spec, "listOf(element) not found")
        if (bindingCount > 1 && listOfVararg == null) return skip(spec, "listOf(vararg) not found")
        if (KoinPluginLogger.verifyGeneratedKeysEnabled && (indexKeyFunction == null || errorFunction == null || definitionQualifierGetter == null || definitionScopeQualifierGetter == null)) {
            return skip(spec, "verification symbols not found")
        }
        return true
    }

    /**
     * Emit the registration block. [receiver] is the `Module` (root definitions) or the `ScopeDSL`
     * (scoped definitions) the generated lambda receives; [definitionLambda] is the already-built
     * `Scope.(ParametersHolder) -> T`.
     */
    fun emit(spec: Spec, receiver: IrValueParameter, definitionLambda: IrExpression, builder: DeclarationIrBuilder): IrExpression =
        emit(spec, builder.irGet(receiver), definitionLambda, builder, returnDefinition = false)

    /**
     * [receiverExpr] evaluates to the `Module` (root) or `ScopeDSL` (scoped) — a lambda's `<this>`
     * or any expression; it is evaluated exactly once. With [returnDefinition] the block's value is
     * a `KoinDefinition<T>`, so a DSL call site that chains `.bind(...)` / `withOptions { }` on the
     * result keeps working; without it the block is Unit (annotation-generated modules).
     */
    fun emit(spec: Spec, receiverExpr: IrExpression, definitionLambda: IrExpression, builder: DeclarationIrBuilder, returnDefinition: Boolean): IrExpression {
        val kClass = kClassClass!!
        val targetType = spec.targetType.defaultType
        val kClassStar = kClass.symbol.starProjectedType
        val primaryKey = indexKeyOf(spec.targetType, spec.qualifier, spec.scope)
        val bindings = allBindings(spec)
        val secondaryKeys = bindings.map { indexKeyOf(it, spec.qualifier, spec.scope) }

        val resultType = if (returnDefinition) koinDefinitionClass!!.typeWith(targetType) else context.irBuiltIns.unitType
        return builder.irBlock(resultType = resultType) {
            val moduleExpr: IrExpression
            val scopeQualifierExpr: IrExpression
            when (spec.scope) {
                ScopeSpec.Root -> {
                    moduleExpr = receiverExpr
                    scopeQualifierExpr = irCall(rootScopeQualifierGetter!!.symbol).apply {
                        dispatchReceiver = irGetObject(scopeRegistryCompanion!!.symbol)
                    }
                }
                is ScopeSpec.Typed, is ScopeSpec.Named -> {
                    // Two reads of the ScopeDSL: reuse a plain variable read directly, otherwise
                    // evaluate the expression once into a temporary.
                    val scopeDsl: () -> IrExpression = if (receiverExpr is IrGetValue) {
                        { irGet(receiverExpr.symbol.owner) }
                    } else {
                        val tmp = irTemporary(receiverExpr, nameHint = "koinScopeDsl"); { irGet(tmp) }
                    }
                    moduleExpr = irCall(scopeDslModuleGetter!!.symbol).apply { dispatchReceiver = scopeDsl() }
                    scopeQualifierExpr = irCall(scopeDslQualifierGetter!!.symbol).apply { dispatchReceiver = scopeDsl() }
                }
            }
            val module = irTemporary(moduleExpr, nameHint = "koinModule")

            val kindExpr = IrGetEnumValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, kindClass!!.defaultType, kindEntry(spec.definitionType).symbol)
            val secondaryTypesExpr = secondaryTypesList(bindings, kClass, kClassStar)

            val definitionCall = irCallConstructor(beanDefinitionConstructor!!.symbol, listOf(targetType)).apply {
                putRegularArgument(0, scopeQualifierExpr)
                putRegularArgument(1, classReference(spec.targetType, kClass))
                putRegularArgument(2, qualifierExtractor.createQualifierCall(spec.qualifier, builder) ?: irNull())
                putRegularArgument(3, definitionLambda)
                putRegularArgument(4, kindExpr)
                putRegularArgument(5, secondaryTypesExpr)
                putRegularArgument(6, irNull())
            }
            val definition = irTemporary(definitionCall, nameHint = "koinDef")

            val factoryConstructor = when (spec.definitionType) {
                DefinitionType.SINGLE -> singleFactoryConstructor!!
                DefinitionType.FACTORY, DefinitionType.VIEW_MODEL, DefinitionType.WORKER -> factoryFactoryConstructor!!
                DefinitionType.SCOPED -> scopedFactoryConstructor!!
            }
            val factoryCall = irCallConstructor(factoryConstructor.symbol, listOf(targetType)).apply {
                putRegularArgument(0, irGet(definition))
                if (spec.definitionType == DefinitionType.SCOPED) putRegularArgument(1, irTrue()) // holdInstance, the ScopedInstanceFactory default
            }
            val factory = irTemporary(factoryCall, nameHint = "koinFactory")

            +irCall(saveMapping!!.symbol).apply {
                dispatchReceiver = irGet(module)
                putRegularArgument(0, irString(primaryKey))
                putRegularArgument(1, irGet(factory))
            }
            for (key in secondaryKeys) {
                +irCall(saveMapping!!.symbol).apply {
                    dispatchReceiver = irGet(module)
                    putRegularArgument(0, irString(key))
                    putRegularArgument(1, irGet(factory))
                }
            }
            if (spec.createdAtStart && spec.definitionType == DefinitionType.SINGLE) {
                +irCall(prepareForCreationAtStart!!.symbol).apply {
                    dispatchReceiver = irGet(module)
                    putRegularArgument(0, irGet(factory))
                }
            }

            if (KoinPluginLogger.verifyGeneratedKeysEnabled) {
                val checked = listOf(spec.targetType to primaryKey) + bindings.zip(secondaryKeys)
                for ((type, key) in checked) {
                    val runtimeKey = irCall(indexKeyFunction!!.symbol).apply {
                        putRegularArgument(0, classReference(type, kClass))
                        putRegularArgument(1, irCall(definitionQualifierGetter!!.symbol).apply { dispatchReceiver = irGet(definition) })
                        putRegularArgument(2, irCall(definitionScopeQualifierGetter!!.symbol).apply { dispatchReceiver = irGet(definition) })
                    }
                    +irIfThen(
                        context.irBuiltIns.unitType,
                        irNotEquals(runtimeKey, irString(key)),
                        irCall(errorFunction!!.symbol).apply {
                            putRegularArgument(0, irString("Koin compiler plugin: compile-time index key '$key' for ${type.fqNameWhenAvailable} does not match indexKey() at runtime"))
                        },
                    )
                }
            }

            // Must stay the last statement: it is the block's value for chained bind()/withOptions.
            if (returnDefinition) {
                +irCallConstructor(koinDefinitionConstructor!!.symbol, listOf(targetType)).apply {
                    putRegularArgument(0, irGet(module))
                    putRegularArgument(1, irGet(factory))
                }
            }
        }
    }

    // ── key derivation ───────────────────────────────────────────────────────────────────────────

    fun indexKeyOf(type: IrClass, qualifier: QualifierValue?, scope: ScopeSpec): String =
        "${platformClassName(type)!!}:${qualifierKey(qualifier)!!}:${scopeKey(scope)!!}"

    /**
     * What `KoinPlatformTools.getClassName(T::class)` returns for [irClass] on the target platform,
     * or null when we are not certain.
     */
    fun platformClassName(irClass: IrClass): String? {
        val classId = irClass.classId ?: return null
        if (classId.isLocal) return null
        val packageName = classId.packageFqName.asString()
        // kotlin.String::class.java.name is java.lang.String: mapped types are not derivable by name.
        if (packageName == "kotlin" || packageName.startsWith("kotlin.") || packageName == "java" || packageName.startsWith("java.")) return null
        val relative = classId.relativeClassName.asString()
        val platform = context.platform
        return when {
            platform.isJs() || platform.isWasm() -> classId.shortClassName.asString()
            platform.isNative() -> if (packageName.isEmpty()) relative else "$packageName.$relative"
            platform.isJvm() -> (if (packageName.isEmpty()) "" else "$packageName.") + relative.replace('.', '$')
            else -> null
        }
    }

    private fun qualifierKey(qualifier: QualifierValue?): String? = when (qualifier) {
        null -> ""
        is QualifierValue.StringQualifier -> qualifier.name
        is QualifierValue.TypeQualifier -> platformClassName(qualifier.irClass)
    }

    private fun scopeKey(scope: ScopeSpec): String? = when (scope) {
        ScopeSpec.Root -> ROOT_SCOPE_ID
        is ScopeSpec.Typed -> platformClassName(scope.scopeClass)
        is ScopeSpec.Named -> scope.name
    }

    // ── IR helpers ───────────────────────────────────────────────────────────────────────────────

    private fun org.jetbrains.kotlin.ir.builders.IrBuilderWithScope.secondaryTypesList(bindings: List<IrClass>, kClass: IrClass, kClassStar: IrType): IrExpression {
        return when (bindings.size) {
            0 -> irCall(emptyListFunction!!.symbol).apply { putTypeArgumentCompat(0, kClassStar) }
            1 -> irCall(listOfSingle!!.symbol).apply {
                putTypeArgumentCompat(0, kClassStar)
                putRegularArgument(0, classReference(bindings[0], kClass))
            }
            else -> irCall(listOfVararg!!.symbol).apply {
                putTypeArgumentCompat(0, kClassStar)
                val elements: List<IrVarargElement> = bindings.map { classReference(it, kClass) }
                putRegularArgument(0, IrVarargImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, context.irBuiltIns.arrayClass.typeWith(kClassStar), kClassStar, elements))
            }
        }
    }

    private fun classReference(irClass: IrClass, kClass: IrClass): IrExpression = IrClassReferenceImpl(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = kClass.typeWith(irClass.defaultType),
        symbol = irClass.symbol,
        classType = irClass.defaultType,
    )

    // Explicit bindings plus the one the satellite DSL adds (deduplicated: a class may already
    // declare it, and the runtime DSL's bind() would index it twice too).
    private fun allBindings(spec: Spec): List<IrClass> {
        val implicit = implicitBinding(spec.definitionType) ?: return spec.bindings
        return if (spec.bindings.any { it.symbol == implicit.symbol }) spec.bindings else spec.bindings + implicit
    }

    private fun implicitBinding(definitionType: DefinitionType): IrClass? = when (definitionType) {
        DefinitionType.VIEW_MODEL -> viewModelClass
        DefinitionType.WORKER -> listenableWorkerClass
        else -> null
    }

    private fun kindEntry(definitionType: DefinitionType): IrEnumEntry {
        val name = when (definitionType) {
            DefinitionType.SINGLE -> "Singleton"
            DefinitionType.FACTORY, DefinitionType.VIEW_MODEL, DefinitionType.WORKER -> "Factory"
            DefinitionType.SCOPED -> "Scoped"
        }
        return kindClass!!.declarations.filterIsInstance<IrEnumEntry>().first { it.name.asString() == name }
    }

    private fun referenceClass(packageName: FqName, name: String): IrClass? =
        context.referenceClass(ClassId(packageName, Name.identifier(name)))?.owner

    private fun IrClass.simpleFunction(name: String, predicate: (IrSimpleFunction) -> Boolean): IrSimpleFunction? =
        declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == name && predicate(it) }

    private fun IrClass.propertyGetter(name: String): IrSimpleFunction? =
        properties.firstOrNull { it.name.asString() == name }?.getter

    private fun topLevelFunction(packageName: FqName, name: String, predicate: (IrSimpleFunction) -> Boolean): IrSimpleFunction? =
        context.referenceFunctions(CallableId(packageName, Name.identifier(name))).map { it.owner }.firstOrNull(predicate)

    private fun skip(spec: Spec, reason: String): Boolean {
        KoinPluginLogger.debug { "Direct registration skipped for ${spec.targetType.fqNameWhenAvailable}: $reason — using the DSL call" }
        return false
    }

    private companion object {
        // ScopeRegistry.ROOT_SCOPE_ID — the value of rootScopeQualifier.toString(), unchanged since Koin 2.
        const val ROOT_SCOPE_ID = "_root_"
    }
}
