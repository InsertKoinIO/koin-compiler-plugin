package org.koin.compiler.plugin

import org.jetbrains.kotlin.ir.expressions.IrCall
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Registry of `IrCall` nodes the plugin itself generated as the mechanical realization of an
 * ALREADY-derived structural requirement — every get()/getOrNull()/inject()/getAll()/
 * getProperty(...) call `KoinArgumentGenerator` builds — never an independent call site to
 * separately validate.
 *
 * Why this exists: `KoinDSLTransformer.collectCallSiteIfResolutionFunction` runs on every IrCall
 * in the module, including declarations an EARLIER phase (`KoinAnnotationProcessor`, Phase 1)
 * already generated before `KoinDSLTransformer`'s own walk (Phase 2) begins. By the time Phase 2
 * sees a generated `scope.get<Repo>()` call inside e.g. an annotation-derived
 * `single<Service> { Service(get()) }` body, it's just an ordinary IrCall — indistinguishable
 * from one a user wrote by hand inside an opaque DSL lambda (the shape that DOES need call-site
 * validation, since its requirements are deliberately never derived). Without this registry,
 * tracking `Scope.get<T>()` as a resolution function double-reports every already-structurally-
 * derived requirement: once via BindingRegistry (KOIN-D001, from the constructor) and once more
 * via the now-visible generated get() call (KOIN-D002) — redundant by construction, since codegen
 * inserts exactly one get() per already-checked parameter, always.
 *
 * Deliberately NOT `IrCall.origin`: that field is real IR-level metadata rendered by IR-dump
 * tooling (`IrTextDumpHandler`) — every golden `.fir.ir.txt` file that happens to contain a
 * plugin-generated resolution call would show it, a much bigger blast radius than this specific
 * problem, purely to carry a fact no dump reader needs. A private, identity-keyed registry never
 * touches the IR tree's own visible shape.
 *
 * Same isolation shape as [PropertyValueRegistry]/[ProvidedTypeRegistry]: a compiler daemon JVM
 * serves multiple compilations concurrently, so state must be isolated PER COMPILATION, not one
 * shared global — InheritableThreadLocal so IR fan-out threads spawned within ONE compilation
 * share the same set, while distinct compilations never see each other's calls. The identity set
 * itself is wrapped synchronized, since those fan-out threads can mutate/read it concurrently
 * (unlike `ConcurrentHashMap.newKeySet()` used by the sibling registries, `IdentityHashMap` has no
 * built-in concurrent variant).
 */
object GeneratedResolutionCallRegistry {

    private val generatedCallsHolder: InheritableThreadLocal<MutableSet<IrCall>> =
        object : InheritableThreadLocal<MutableSet<IrCall>>() {
            override fun initialValue(): MutableSet<IrCall> =
                Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
        }

    private val generatedCalls: MutableSet<IrCall> get() = generatedCallsHolder.get()

    /** Mark [call] as one this plugin generated — never an independent call site to validate. */
    fun markGenerated(call: IrCall) {
        generatedCalls.add(call)
    }

    /** True if [call] was built by this plugin's own codegen (see [markGenerated]). */
    fun isGenerated(call: IrCall): Boolean = call in generatedCalls

    /** Clear the registry (called at the start of each fresh compilation). */
    fun clear() {
        generatedCalls.clear()
    }
}
