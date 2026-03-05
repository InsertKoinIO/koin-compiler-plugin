package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.ProvidedTypeRegistry

/**
 * A4: Call-site validation for koinViewModel<T>() and koinNavViewModel<T>().
 *
 * Validates that the type argument T used in Compose ViewModel resolution functions
 * is a declared Koin definition. Uses two strategies depending on context:
 *
 * **When A3 assembled graph is available** (startKoin/koinConfiguration present):
 * Checks if T is in the assembled graph's provided types. This catches cases where
 * T has a definition annotation but is not included in any loaded module.
 *
 * **Fallback** (no startKoin in this compilation unit):
 * 1. Annotation check: Inspects T's class for definition annotations
 * 2. Module definitions check: Looks up T in collected definitions from local @Module classes
 *
 * Runs as Phase 3.5 (after annotation processing and startKoin transformation,
 * before monitor processing). Read-only pass — no IR transformation.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class KoinCallSiteValidator(
    private val annotationProcessor: KoinAnnotationProcessor,
    private val assembledGraphTypes: Set<String>
) : IrElementTransformerVoid() {

    /** FQ name strings of functions to intercept. */
    private val interceptedFqNames: Set<String> =
        KoinAnnotationFqNames.CALL_SITE_RESOLUTION_FUNCTIONS.map { it.asString() }.toSet()

    /** All definition annotation FQ names (Koin + JSR-330). */
    private val definitionAnnotationFqNames: Set<String> =
        (KoinAnnotationFqNames.KOIN_DEFINITION_ANNOTATIONS.map { it.asString() } +
            KoinAnnotationFqNames.JAKARTA_SINGLETON.asString() +
            KoinAnnotationFqNames.JAVAX_SINGLETON.asString()).toSet()

    /** Lazily built set of all known provided type FqNames from module definitions (return types + bindings). */
    private val moduleProvidedTypes: Set<String> by lazy { buildModuleProvidedTypes() }

    /** Current file being visited (for source location in error messages). */
    private var currentFile: IrFile? = null

    override fun visitFile(declaration: IrFile): IrFile {
        currentFile = declaration
        return super.visitFile(declaration)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        val calleeFqName = callee.fqNameWhenAvailable?.asString()

        if (calleeFqName == null || calleeFqName !in interceptedFqNames) {
            return super.visitCall(expression)
        }

        // Must have a type parameter (the reified T)
        if (callee.typeParameters.isEmpty()) {
            return super.visitCall(expression)
        }

        // Extract T from koinViewModel<T>()
        val typeArg = expression.getTypeArgument(0)
            ?: return super.visitCall(expression)
        val targetClass = (typeArg.classifierOrNull as? IrClassSymbol)?.owner
            ?: return super.visitCall(expression)
        val targetFqName = targetClass.fqNameWhenAvailable?.asString()
            ?: return super.visitCall(expression)

        // Skip @Provided types
        if (ProvidedTypeRegistry.isProvided(targetFqName)) {
            KoinPluginLogger.debug { "A4: Skip $targetFqName (@Provided)" }
            return super.visitCall(expression)
        }

        // Skip whitelisted framework types
        if (BindingRegistry.isWhitelistedType(targetFqName)) {
            KoinPluginLogger.debug { "A4: Skip $targetFqName (framework whitelist)" }
            return super.visitCall(expression)
        }

        if (assembledGraphTypes.isNotEmpty()) {
            // A3 assembled the full graph — check against the actual runtime types
            if (targetFqName in assembledGraphTypes) {
                KoinPluginLogger.debug { "A4: OK $calleeFqName<$targetFqName>() — found in assembled graph" }
                return super.visitCall(expression)
            }
        } else {
            // No startKoin/koinConfiguration in this compilation unit — fall back to heuristics
            // Strategy 1: Check if the class itself has a definition annotation
            if (hasDefinitionAnnotation(targetClass)) {
                KoinPluginLogger.debug { "A4: OK $calleeFqName<$targetFqName>() — has definition annotation" }
                return super.visitCall(expression)
            }

            // Strategy 2: Check if the type is in module-provided definitions
            if (targetFqName in moduleProvidedTypes) {
                KoinPluginLogger.debug { "A4: OK $calleeFqName<$targetFqName>() — found in module definitions" }
                return super.visitCall(expression)
            }
        }

        // Not found — report error with source location
        val callName = calleeFqName.substringAfterLast(".")
        val file = currentFile
        val filePath = file?.fileEntry?.name
        val line = if (file != null && expression.startOffset >= 0) {
            file.fileEntry.getLineNumber(expression.startOffset) + 1 // 0-based → 1-based
        } else 0
        val column = if (file != null && expression.startOffset >= 0) {
            file.fileEntry.getColumnNumber(expression.startOffset) + 1
        } else 0

        KoinPluginLogger.error(
            "Missing definition: $targetFqName\n" +
            "  resolved by: $callName<${targetClass.name}>()\n" +
            "  No matching definition found in any declared module.\n" +
            "  Check your declaration with Annotation or DSL.",
            filePath, line, column
        )

        return super.visitCall(expression)
    }

    /**
     * Check if the class has any Koin definition annotation (@KoinViewModel, @Singleton, @Factory, etc.)
     * or JSR-330 @Singleton. This works even for classes from dependency JARs since annotation
     * metadata is preserved in the IR.
     */
    private fun hasDefinitionAnnotation(irClass: IrClass): Boolean {
        return irClass.annotations.any { annotation ->
            annotation.type.classFqName?.asString() in definitionAnnotationFqNames
        }
    }

    private fun buildModuleProvidedTypes(): Set<String> {
        val types = mutableSetOf<String>()

        val allDefinitions = annotationProcessor.getAllKnownDefinitions()
        for (def in allDefinitions) {
            def.returnTypeClass.fqNameWhenAvailable?.asString()?.let { types.add(it) }
            for (binding in def.bindings) {
                binding.fqNameWhenAvailable?.asString()?.let { types.add(it) }
            }
        }

        KoinPluginLogger.debug {
            "A4: Built module provided types: ${types.size} types from ${annotationProcessor.collectedModuleClasses.size} modules"
        }
        return types
    }
}
