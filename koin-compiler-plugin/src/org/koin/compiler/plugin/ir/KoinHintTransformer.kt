package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.fir.KoinModuleFirGenerator

/**
 * Generates IR bodies for FIR-generated hint functions and registers them
 * as metadata-visible for cross-module discovery.
 *
 * Hint functions are registered via metadataDeclarationRegistrar so they appear
 * in compiled metadata and can be discovered by downstream modules.
 *
 * Hint functions are in org.koin.plugin.hints package with specific patterns:
 * - Configuration hints: configuration_default, configuration_test, configuration_prod, etc.
 * - Definition hints: definition_single, definition_factory, definition_viewmodel, etc.
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
class KoinHintTransformer(
    private val context: IrPluginContext
) : IrElementTransformerVoid() {

    // Package for all hint functions
    private val hintsPackage = KoinModuleFirGenerator.HINTS_PACKAGE

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrSimpleFunction {
        // Check if this is a hint function in the hints package that needs a body
        val fqName = declaration.fqNameWhenAvailable
        val parentPackage = fqName?.parent()

        // Check if parent package is the hints package
        val functionName = declaration.name.asString()
        val isConfigurationHint = parentPackage == hintsPackage &&
            KoinModuleFirGenerator.labelFromHintFunctionName(functionName) != null
        val isDefinitionHint = parentPackage == hintsPackage &&
            KoinModuleFirGenerator.definitionTypeFromHintFunctionName(functionName) != null
        val isFunctionDefinitionHint = parentPackage == hintsPackage &&
            KoinModuleFirGenerator.definitionTypeFromFunctionHintName(functionName) != null
        // Note: componentscan_* / componentscanfunc_* hints are generated in IR (KoinAnnotationProcessor)
        // with non-null bodies, so they don't need processing here.

        if ((isConfigurationHint || isDefinitionHint || isFunctionDefinitionHint) && declaration.body == null) {
            // Generate body for hint function: error("Stub!")
            declaration.body = generateHintFunctionBody(declaration)

            // CRITICAL: Register for downstream visibility
            // This makes the hint function visible in compiled metadata
            context.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(declaration)
            KoinPluginLogger.debug { "Registered hint: $functionName" }
        }
        return declaration
    }

    /**
     * Generate body for hint functions: error("Stub!")
     * The function body just throws - it's never called at runtime.
     */
    private fun generateHintFunctionBody(function: IrSimpleFunction): IrBody {
        val builder = DeclarationIrBuilder(context, function.symbol)

        val errorFunction = context.referenceFunctions(
            CallableId(FqName("kotlin"), Name.identifier("error"))
        ).first()

        return builder.irBlockBody {
            +irCall(errorFunction).apply {
                putValueArgument(0, irString("Stub!"))
            }
        }
    }
}
