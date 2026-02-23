package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * Resolves module() extension functions for Koin @Module classes.
 *
 * Implements a multi-strategy lookup chain:
 * 1. Current module fragment (same compilation)
 * 2. Context reference functions (compiled dependencies)
 * 3. File facade class lookup (when FIR metadata is stale)
 * 4. Class name convention fallback (JAR classes)
 */
@OptIn(DeprecatedForRemovalCompilerApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class ModuleFunctionResolver(
    private val context: IrPluginContext,
    private val moduleFragment: IrModuleFragment
) {

    /**
     * Build: ModuleClass().module() for classes, or ModuleObject.module() for objects
     */
    fun buildModuleGetCall(
        moduleClass: IrClass,
        builder: DeclarationIrBuilder
    ): IrExpression? {
        val instanceExpression = if (moduleClass.isObject) {
            builder.irGetObject(moduleClass.symbol)
        } else {
            val constructor = moduleClass.primaryConstructor ?: return null
            builder.irCallConstructor(constructor.symbol, emptyList())
        }

        // Try to find the function in the current module fragment (same compilation)
        val moduleFunction = findModuleFunction(moduleClass)
        if (moduleFunction != null) {
            KoinPluginLogger.debug { "  -> Found module() function for ${moduleClass.name}" }
            return builder.irCall(moduleFunction.symbol).apply {
                extensionReceiver = instanceExpression
            }
        }

        // Fall back to finding the function via context (for compiled dependencies)
        val function = findModuleFunctionViaContext(moduleClass)
        if (function != null) {
            KoinPluginLogger.debug { "  -> Using module() function from context for ${moduleClass.name}" }
            return builder.irCall(function.symbol).apply {
                extensionReceiver = instanceExpression
            }
        }

        KoinPluginLogger.debug { "  -> ERROR: Could not find module() function for ${moduleClass.name}" }
        return null
    }

    /**
     * Find the module extension function in the current module fragment (same compilation).
     */
    private fun findModuleFunction(moduleClass: IrClass): IrSimpleFunction? {
        val targetFqName = moduleClass.fqNameWhenAvailable?.asString()

        for (file in moduleFragment.files) {
            for (func in file.declarations.filterIsInstance<IrSimpleFunction>()) {
                if (func.name.asString() != "module") continue
                val receiverFqName = func.extensionReceiverParameter?.type?.classFqName?.asString()
                if (receiverFqName == targetFqName) {
                    KoinPluginLogger.debug { "  -> Found module() function in module fragment for $targetFqName" }
                    return func
                }
            }
        }

        return null
    }

    /**
     * Find the module function via context.referenceFunctions (compiled dependencies fallback).
     */
    private fun findModuleFunctionViaContext(moduleClass: IrClass): IrSimpleFunction? {
        val packageName = moduleClass.fqNameWhenAvailable?.parent() ?: FqName.ROOT

        val functionCandidates = context.referenceFunctions(
            CallableId(packageName, Name.identifier("module"))
        )
        KoinPluginLogger.debug { "  -> Searching for module function in $packageName, found ${functionCandidates.size} candidates" }

        for ((idx, func) in functionCandidates.withIndex()) {
            val owner = func.owner
            val extensionReceiverType = owner.extensionReceiverParameter?.type
            val receiverFqName = (extensionReceiverType?.classifierOrNull as? IrClassSymbol)?.owner?.fqNameWhenAvailable
            val parent = owner.parent
            val parentInfo = when (parent) {
                is IrFile -> "file=${parent.name}"
                is IrClass -> "facade=${parent.name}"
                else -> "parent=${parent.javaClass.simpleName}"
            }
            val origin = owner.origin.toString()
            val symbolId = System.identityHashCode(func)
            KoinPluginLogger.debug { "    -> Candidate $idx: receiver=$receiverFqName, $parentInfo, origin=$origin, symbolId=$symbolId" }
        }

        val matchingCandidates = functionCandidates.filter { func ->
            val extensionReceiverType = func.owner.extensionReceiverParameter?.type
            val receiverFqName = (extensionReceiverType?.classifierOrNull as? IrClassSymbol)?.owner?.fqNameWhenAvailable
            receiverFqName == moduleClass.fqNameWhenAvailable
        }

        KoinPluginLogger.debug { "    -> Found ${matchingCandidates.size} matching candidates for ${moduleClass.name}" }

        val result = if (matchingCandidates.size > 1) {
            selectCorrectCandidate(matchingCandidates, moduleClass, packageName)
        } else {
            matchingCandidates.firstOrNull()?.owner
        }

        if (result != null) {
            val parentFileName = (result.parent as? IrFile)?.name ?: "unknown"
            KoinPluginLogger.debug { "  -> Selected function for ${moduleClass.name} in file: $parentFileName" }
            return result
        }

        return findModuleFunctionViaFileFacade(moduleClass, packageName)
    }

    /**
     * When we have duplicate candidates (stale FIR metadata), select the correct one
     * by looking up the file facade class that actually contains the function in bytecode.
     */
    private fun selectCorrectCandidate(
        candidates: List<IrFunctionSymbol>,
        moduleClass: IrClass,
        packageName: FqName
    ): IrSimpleFunction? {
        val containingFile = moduleClass.parent as? IrFile
        if (containingFile != null) {
            val fileName = containingFile.name
            if (fileName.isNotEmpty()) {
                val baseName = fileName.removeSuffix(".kt")
                val expectedFacadeClassName = "${baseName}Kt"
                KoinPluginLogger.debug { "    -> Expected file facade from source: ${packageName.asString()}.$expectedFacadeClassName" }

                val facadeClassId = ClassId(packageName, Name.identifier(expectedFacadeClassName))
                val facadeClassSymbol = context.referenceClass(facadeClassId)
                if (facadeClassSymbol != null) {
                    val facadeClass = facadeClassSymbol.owner
                    val moduleFunction = facadeClass.functions.firstOrNull { func ->
                        func.name.asString() == "module" &&
                        func.extensionReceiverParameter?.type?.classFqName == moduleClass.fqNameWhenAvailable
                    }
                    if (moduleFunction != null) {
                        KoinPluginLogger.debug { "    -> Found correct function in file facade class" }
                        return moduleFunction
                    }
                }
            }
        }

        KoinPluginLogger.debug { "    -> Module class is from JAR, ${candidates.size} duplicate candidates" }

        for (candidate in candidates) {
            val owner = candidate.owner as? IrSimpleFunction ?: continue
            if (owner.parent is IrFile) {
                KoinPluginLogger.debug { "    -> Using candidate from current compilation" }
                return owner
            }
        }

        val moduleClassName = moduleClass.name.asString()

        for ((idx, candidate) in candidates.withIndex()) {
            val owner = candidate.owner as? IrSimpleFunction ?: continue
            val parent = owner.parent
            when (parent) {
                is IrClass -> KoinPluginLogger.debug { "    -> Candidate $idx parent facade: ${parent.name}" }
                is IrFile -> KoinPluginLogger.debug { "    -> Candidate $idx parent file: ${parent.name}" }
                else -> KoinPluginLogger.debug { "    -> Candidate $idx parent: ${parent.javaClass.simpleName}" }
            }
        }

        val classBasedFacadeName = "${moduleClassName}Kt"
        val classBasedFunc = lookupFunctionInFacade(packageName, classBasedFacadeName, moduleClass)
        if (classBasedFunc != null) {
            KoinPluginLogger.debug { "    -> Found function in class-based facade: $classBasedFacadeName" }
            return classBasedFunc
        }

        val triedFacades = mutableSetOf(classBasedFacadeName)
        val commonFacadePatterns = listOf(
            "${moduleClassName}TestKt",
            "${moduleClassName.removeSuffix("Module")}TestKt",
            "${moduleClassName}ConfigTestKt"
        )

        for (facadeName in commonFacadePatterns) {
            if (facadeName in triedFacades) continue
            triedFacades.add(facadeName)

            val func = lookupFunctionInFacade(packageName, facadeName, moduleClass)
            if (func != null) {
                KoinPluginLogger.debug { "    -> Found function in pattern-based facade: $facadeName" }
                return func
            }
        }

        KoinPluginLogger.debug { "    -> No function found via facade lookup, trying last candidate" }
        return candidates.lastOrNull()?.owner as? IrSimpleFunction
    }

    /**
     * Look up a module() function in a specific file facade class.
     */
    private fun lookupFunctionInFacade(
        packageName: FqName,
        facadeName: String,
        moduleClass: IrClass
    ): IrSimpleFunction? {
        val facadeClassId = ClassId(packageName, Name.identifier(facadeName))
        val facadeClassSymbol = try {
            context.referenceClass(facadeClassId)
        } catch (e: Throwable) {
            KoinPluginLogger.debug { "      -> Exception looking up $facadeName: ${e.message}" }
            return null
        }
        if (facadeClassSymbol == null) {
            KoinPluginLogger.debug { "      -> Facade class $facadeName not found" }
            return null
        }

        val facadeClass = facadeClassSymbol.owner
        val allFunctions = facadeClass.functions.toList()
        val moduleFunctions = allFunctions.filter { it.name.asString() == "module" }

        if (moduleFunctions.isNotEmpty()) {
            KoinPluginLogger.debug { "      -> $facadeName has ${moduleFunctions.size} module() functions" }
            for (func in moduleFunctions) {
                val receiverType = func.extensionReceiverParameter?.type?.classFqName
                KoinPluginLogger.debug { "        -> module() receiver: $receiverType (want: ${moduleClass.fqNameWhenAvailable})" }
            }
        }

        return moduleFunctions.firstOrNull { func ->
            func.extensionReceiverParameter?.type?.classFqName == moduleClass.fqNameWhenAvailable
        }
    }

    /**
     * Try to find the module() function by looking at the file facade class.
     */
    private fun findModuleFunctionViaFileFacade(moduleClass: IrClass, packageName: FqName): IrSimpleFunction? {
        KoinPluginLogger.debug { "  -> Trying file facade lookup for ${moduleClass.name}" }
        KoinPluginLogger.debug { "    -> moduleClass.parent type: ${moduleClass.parent.javaClass.simpleName}" }

        val containingFile = moduleClass.parent as? IrFile
        if (containingFile == null) {
            KoinPluginLogger.debug { "    -> moduleClass.parent is not IrFile, trying to find file via metadata" }
            return findModuleFunctionViaClassName(moduleClass, packageName)
        }
        val fileName = containingFile.name
        if (fileName.isEmpty()) {
            KoinPluginLogger.debug { "    -> fileName is empty" }
            return null
        }

        val baseName = fileName.removeSuffix(".kt")
        val facadeClassName = "${baseName}Kt"
        val facadeClassId = ClassId(packageName, Name.identifier(facadeClassName))

        KoinPluginLogger.debug { "  -> Trying file facade class: ${facadeClassId.asFqNameString()}" }

        val facadeClassSymbol = context.referenceClass(facadeClassId)
        if (facadeClassSymbol == null) {
            KoinPluginLogger.debug { "  -> File facade class not found" }
            return null
        }

        val facadeClass = facadeClassSymbol.owner
        val moduleFunction = facadeClass.functions.firstOrNull { func ->
            func.name.asString() == "module" &&
            func.extensionReceiverParameter?.type?.classFqName == moduleClass.fqNameWhenAvailable
        }

        if (moduleFunction != null) {
            KoinPluginLogger.debug { "  -> Found module() in file facade class" }
        }
        return moduleFunction
    }

    /**
     * For classes from JARs where we can't get the source file name,
     * try common naming conventions to find the module() function.
     */
    private fun findModuleFunctionViaClassName(moduleClass: IrClass, packageName: FqName): IrSimpleFunction? {
        val className = moduleClass.name.asString()

        val possibleFacadeNames = listOf(
            "${className}Kt",
            "${className.removeSuffix("Module")}ModuleKt",
        )

        for (facadeName in possibleFacadeNames) {
            val facadeClassId = ClassId(packageName, Name.identifier(facadeName))
            KoinPluginLogger.debug { "    -> Trying facade class: ${facadeClassId.asFqNameString()}" }

            val facadeClassSymbol = context.referenceClass(facadeClassId)
            if (facadeClassSymbol == null) {
                continue
            }

            val facadeClass = facadeClassSymbol.owner
            val moduleFunction = facadeClass.functions.firstOrNull { func ->
                func.name.asString() == "module" &&
                func.extensionReceiverParameter?.type?.classFqName == moduleClass.fqNameWhenAvailable
            }

            if (moduleFunction != null) {
                KoinPluginLogger.debug { "    -> Found module() in ${facadeName}" }
                return moduleFunction
            }
        }

        return null
    }
}
