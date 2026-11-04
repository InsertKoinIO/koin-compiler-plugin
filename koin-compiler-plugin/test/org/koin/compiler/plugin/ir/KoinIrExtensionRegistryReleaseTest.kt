package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.compiler.plugin.template.runners.AbstractJvmBoxTest
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.junit.jupiter.api.Test
import org.koin.compiler.plugin.GeneratedResolutionCallRegistry
import org.koin.compiler.plugin.PropertyValueRegistry
import org.koin.compiler.plugin.ProvidedTypeRegistry
import kotlin.concurrent.thread
import kotlin.test.assertEquals

/**
 * Regression test: the IR phase must not leave per-compilation registries bound to the thread that
 * ran it.
 *
 * The registries are `InheritableThreadLocal`s holding IR (`IrCall`, `IrProperty`). They used to be
 * cleared only at the start of the next IR phase on the same thread
 * (`KoinAnnotationProcessor.collectAnnotations`). In the Kotlin daemon a compilation runs on an RMI
 * connection thread that outlives it, so such threads kept finished compilations reachable —
 * module IR, FIR sessions, deserialized libraries — until the daemon ran out of heap on multi-module
 * builds.
 *
 * Runs a real compilation through the box-test pipeline (IR generation happens on the calling
 * thread), then inspects the registries' thread-local slots. Golden dumps are disabled: the input is
 * not a box test and its IR shape is covered elsewhere.
 */
class KoinIrExtensionRegistryReleaseTest : AbstractJvmBoxTest() {

    override fun configure(builder: TestConfigurationBuilder) = with(builder) {
        super.configure(this)
        defaultDirectives {
            -CodegenTestDirectives.DUMP_IR
            -FirDiagnosticsDirectives.FIR_DUMP
        }
    }

    @Test
    fun `IR phase unbinds every registry from the compiling thread`() {
        runTest(TEST_FILE)

        val stillBound = registryHolders().mapNotNull { (name, holder) ->
            // A thread started now inherits this thread's value only while the slot is still set;
            // otherwise both sides get their own initialValue(). Checked before any get() on this
            // thread, which would re-create the slot.
            var inherited: Any? = null
            thread { inherited = holder.get() }.join()
            val own = holder.get()
            if (own === inherited) "$name (${sizeOf(own)} entries)" else null
        }
        assertEquals(emptyList(), stillBound, "Registries still bound to the compiling thread after the IR phase")
    }

    @Test
    fun `IR phase empties registries shared with threads that inherited them`() {
        // Threads started while a slot is set (e.g. workers spawned during an earlier compilation on
        // this thread) share the same collection; ThreadLocal.remove() on this thread does not reach
        // them, so the collection itself must be emptied.
        val inherited = registryHolders().map { (name, holder) ->
            holder.get()
            var value: Any? = null
            thread { value = holder.get() }.join()
            name to value
        }

        runTest(TEST_FILE)

        val notEmptied = inherited.filter { (_, value) -> sizeOf(value) != 0 }
            .map { (name, value) -> "$name (${sizeOf(value)} entries)" }
        assertEquals(emptyList(), notEmptied, "Registries still filled for threads that inherited them")
    }

    private fun registryHolders(): List<Pair<String, ThreadLocal<*>>> = listOf(
        holder(GeneratedResolutionCallRegistry::class.java, "generatedCallsHolder"),
        holder(PropertyValueRegistry::class.java, "propertyDefaults"),
        holder(ProvidedTypeRegistry::class.java, "providedTypesHolder"),
    )

    private fun holder(owner: Class<*>, field: String): Pair<String, ThreadLocal<*>> =
        owner.simpleName to owner.getDeclaredField(field).apply { isAccessible = true }.get(null) as ThreadLocal<*>

    private fun sizeOf(value: Any?): Int = when (value) {
        is Collection<*> -> value.size
        is Map<*, *> -> value.size
        else -> error("Unexpected registry value: $value")
    }

    private companion object {
        const val TEST_FILE = "koin-compiler-plugin/testData/registry/all_registries_filled.kt"
    }
}
