package org.koin.compiler.plugin.ir

internal sealed interface DslModuleRoots {
    val moduleIds: Set<String>

    data class Known(override val moduleIds: Set<String>) : DslModuleRoots
    data class Unknown(override val moduleIds: Set<String>, val origin: SourceOrigin?) : DslModuleRoots
}

internal sealed interface DslGraphAssembly {
    data class Known(
        val reachableModuleIds: Set<String>,
        val definitions: List<Definition.DslDef>,
        val unreachableDefinitions: List<Definition.DslDef>,
    ) : DslGraphAssembly

    data class Unknown(
        val definitions: List<Definition.DslDef>,
        val origin: SourceOrigin?,
    ) : DslGraphAssembly
}

internal class DslGraphAssembler(private val hintGenerator: DslHintGenerator) {
    fun assemble(
        localDefinitions: List<Definition.DslDef>,
        roots: DslModuleRoots,
        localIncludes: Map<String, List<String>>,
        modulesWithIncompleteIncludes: Set<String>,
    ): DslGraphAssembly {
        val dependencyDefinitions = hintGenerator.discoverDslDefinitionsFromHints()
        val allDefinitions = localDefinitions + dependencyDefinitions
        if (roots is DslModuleRoots.Unknown) {
            return DslGraphAssembly.Unknown(allDefinitions, roots.origin)
        }

        val reachableModuleIds = reachableModules(
            roots.moduleIds,
            localIncludes,
            modulesWithIncompleteIncludes,
        ) ?: return DslGraphAssembly.Unknown(allDefinitions, null)

        val reachable = mutableListOf<Definition.DslDef>()
        val unreachable = mutableListOf<Definition.DslDef>()
        for (definition in localDefinitions) {
            if (definition.modulePropertyId == null || definition.modulePropertyId in reachableModuleIds) {
                reachable.add(definition)
            } else {
                unreachable.add(definition)
            }
        }
        for (definition in dependencyDefinitions) {
            if (definition.modulePropertyId in reachableModuleIds) {
                reachable.add(definition)
            } else {
                unreachable.add(definition)
            }
        }

        return DslGraphAssembly.Known(reachableModuleIds, reachable, unreachable)
    }

    private fun reachableModules(
        roots: Set<String>,
        localIncludes: Map<String, List<String>>,
        modulesWithIncompleteIncludes: Set<String>,
    ): Set<String>? {
        val reachable = mutableSetOf<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val moduleId = queue.removeFirst()
            if (moduleId in modulesWithIncompleteIncludes) return null
            if (reachable.add(moduleId)) {
                val dependencyIncludes = hintGenerator.discoverModuleIncludesFromHints(moduleId)
                if (dependencyIncludes.incomplete) return null
                for (included in localIncludes[moduleId].orEmpty() + dependencyIncludes.edges) {
                    if (included !in reachable) queue.add(included)
                }
            }
        }
        return reachable
    }
}
