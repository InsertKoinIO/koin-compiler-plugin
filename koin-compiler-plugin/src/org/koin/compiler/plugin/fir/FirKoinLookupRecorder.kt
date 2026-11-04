package org.koin.compiler.plugin.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.koin.compiler.plugin.KoinAnnotationFqNames
import org.koin.compiler.plugin.KoinPluginConstants
import org.koin.compiler.plugin.KoinPluginLogger
import org.koin.compiler.plugin.erasedHintClassId

/**
 * FIR checker that records lookup dependencies for @KoinApplication classes.
 *
 * When a class has @KoinApplication, the Koin plugin discovers @Configuration modules
 * at compile time (via hint functions). But Kotlin's cross-module IC doesn't know about
 * these plugin-generated dependencies — it only tracks source-level references.
 *
 * This checker runs during FIR analysis and records lookup dependencies from the
 * @KoinApplication file to all discovered @Configuration module classes. This way,
 * when a @Configuration module changes (functions added/removed), IC knows to recompile
 * the @KoinApplication file, triggering the safety check.
 *
 * NOTE: As of K2 2.3.x, cross-module IC uses classpath ABI snapshots rather than
 * LookupTracker records. These records may not trigger recompilation for cross-module
 * changes. However, they DO help for within-module IC and may be used by future K2 versions.
 */
class FirKoinLookupRecorder(session: FirSession) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers = object : DeclarationCheckers() {
        override val regularClassCheckers = setOf(KoinApplicationLookupChecker)
    }

    /**
     * Checker that visits @KoinApplication classes and records IC dependencies
     * on all @Configuration modules discovered via hint functions.
     */
    private object KoinApplicationLookupChecker : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {

        private val hintsPackage = FqName(KoinPluginConstants.HINTS_PACKAGE)
        private val koinApplicationClassId = ClassId.topLevel(KoinAnnotationFqNames.KOIN_APPLICATION)

        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(
            declaration: FirRegularClass
        ) {
            // Only process classes with @KoinApplication
            if (!declaration.hasAnnotation(koinApplicationClassId, context.session)) return

            // Get the LookupTracker from compiler configuration (stored in KoinPluginLogger)
            val lookupTracker = KoinPluginLogger.lookupTracker ?: return

            // Get the file path from CheckerContext
            val filePath = context.containingFilePath ?: return

            // Extract configuration labels from @KoinApplication annotation
            val labels = extractConfigurationLabels(declaration)

            KoinPluginLogger.debugFir { "FIR IC: @KoinApplication found: ${declaration.symbol.classId}, labels=$labels, file=$filePath" }

            // Query hint functions for each label and record lookup dependencies
            for (label in labels) {
                val hintName = Name.identifier("${KoinPluginConstants.HINT_FUNCTION_PREFIX}$label")
                val hintFunctions = context.session.symbolProvider
                    .getTopLevelFunctionSymbols(hintsPackage, hintName)

                for (hintFunc in hintFunctions) {
                    // Extract the module class from the hint function parameter
                    val params = hintFunc.valueParameterSymbols
                    val moduleClassId = erasedHintClassId(params.map { it.name }, position = 0)
                        ?: params.firstOrNull()?.resolvedReturnType?.classId
                        ?: continue

                    // Record lookup: this file depends on the module class
                    lookupTracker.record(
                        filePath,
                        Position.NO_POSITION,
                        moduleClassId.packageFqName.asString(),
                        ScopeKind.PACKAGE,
                        moduleClassId.shortClassName.asString()
                    )

                    KoinPluginLogger.debugFir { "FIR IC: Recorded lookup: $filePath -> $moduleClassId" }
                }
            }
        }

        /**
         * Extract configuration labels from @KoinApplication annotation.
         * Returns ["default"] if no labels specified.
         */
        private fun extractConfigurationLabels(declaration: FirRegularClass): List<String> {
            val annotation = declaration.annotations.firstOrNull { ann ->
                ann.annotationTypeRef.coneTypeOrNull?.classId == koinApplicationClassId
            }

            if (annotation == null) return listOf(KoinPluginConstants.DEFAULT_LABEL)

            val labels = mutableListOf<String>()
            val mapping = annotation.argumentMapping
            val args = mapping.mapping

            for ((name, value) in args) {
                if (name.asString() == "configurations") {
                    when (value) {
                        is org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression -> {
                            for (arg in value.arguments) {
                                if (arg is org.jetbrains.kotlin.fir.expressions.FirLiteralExpression) {
                                    val v = arg.value
                                    if (v is String) labels.add(v)
                                }
                            }
                        }
                        is org.jetbrains.kotlin.fir.expressions.FirLiteralExpression -> {
                            val v = value.value
                            if (v is String) labels.add(v)
                        }
                        else -> {}
                    }
                }
            }

            return labels.ifEmpty { listOf(KoinPluginConstants.DEFAULT_LABEL) }
        }
    }
}
