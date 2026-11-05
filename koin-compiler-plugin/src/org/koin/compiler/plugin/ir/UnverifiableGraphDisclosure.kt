package org.koin.compiler.plugin.ir

import org.koin.compiler.plugin.KoinDiagnostic
import org.koin.compiler.plugin.KoinPluginLogger

/**
 * KOIN-W003, anchored at what could not be read: the entry point's own `modules(...)` argument,
 * a local module's `includes(...)` argument or returned value, or, for a dependency module whose
 * own compilation could not read its `includes()`, the `modules(...)` call that loads it here.
 *
 * @param includedBy the includes tree walked by [CallSiteValidator]: child module id to its includer
 */
internal class UnverifiableGraphDisclosure(
    private val entryModulesUnreadable: UnreadableModuleArgument?,
    private val entryModulesIncompleteOrigin: SourceOrigin?,
    private val includedBy: Map<String, String> = emptyMap(),
    private val incompleteIncludesArguments: Map<String, UnreadableModuleArgument> = emptyMap(),
    private val entryModuleOrigins: Map<String, SourceOrigin> = emptyMap(),
    private val moduleDeclarationOrigins: Map<String, SourceOrigin> = emptyMap(),
) {

    /** [reached]: the module whose `includes()` stopped the walk, or null for the entry point's own argument. */
    fun report(reached: String?) {
        val localIncludes = reached?.let { incompleteIncludesArguments[it] }
        val localDeclaration = reached?.let { moduleDeclarationOrigins[it] }
        val (subject, unreadable, origin) = when {
            reached == null -> Triple(
                "this entry point",
                entryModulesUnreadable?.description,
                entryModulesUnreadable?.origin ?: entryModulesIncompleteOrigin,
            )
            localIncludes != null ->
                Triple("module '${reached.substringAfterLast('.')}'", localIncludes.description, localIncludes.origin)
            localDeclaration != null ->
                Triple("module '${reached.substringAfterLast('.')}'", "what it returns", localDeclaration)
            else -> Triple(
                "module '${reached.substringAfterLast('.')}', loaded here,",
                "its includes() (declared in another Gradle module)",
                entryModuleOrigins[rootLoading(reached)],
            )
        }
        KoinPluginLogger.report(
            KoinDiagnostic.UnverifiableDynamicGraph(subject, unreadable),
            origin?.filePath,
            origin?.line ?: -1,
        )
    }

    /** The entry-point root [moduleId] was reached from, following the includes tree upward. */
    private fun rootLoading(moduleId: String): String {
        var current = moduleId
        val seen = mutableSetOf<String>()
        while (seen.add(current)) current = includedBy[current] ?: return current
        return current
    }
}
