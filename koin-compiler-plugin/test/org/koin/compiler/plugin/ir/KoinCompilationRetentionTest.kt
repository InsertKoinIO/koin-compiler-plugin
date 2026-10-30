package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.template.runners.AbstractJvmBoxTest
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import kotlin.test.fail

/**
 * Leak guard (#110): once a compilation has finished, nothing in the plugin may keep its IR alive.
 *
 * In the Kotlin daemon every compilation runs on a long-lived thread inside one JVM, so any static
 * or thread-bound reference to IR pins the whole compilation (module IR, FIR sessions, deserialized
 * libraries) — ~20 MB per module per build, until the daemon dies with OutOfMemoryError.
 *
 * This checks the invariant itself rather than a list of known registries: a test-only IR extension
 * weakly captures the [IrModuleFragment], and after the compilation it must be garbage-collectable.
 * Any new static cache / ThreadLocal that holds IR fails here. On failure, plugin ThreadLocals still
 * bound on this thread are listed to point at the culprit.
 */
class KoinCompilationRetentionTest : AbstractJvmBoxTest() {

    override fun configure(builder: TestConfigurationBuilder) = with(builder) {
        super.configure(this)
        defaultDirectives {
            -CodegenTestDirectives.DUMP_IR
            -FirDiagnosticsDirectives.FIR_DUMP
        }
        useConfigurators(::ModuleCaptureConfigurator)
    }

    @Test
    fun `finished compilation is not retained by the plugin`() {
        captured.clear()
        runTest(TEST_FILE)

        val modules = captured.toList()
        captured.clear()
        check(modules.isNotEmpty()) { "IR phase did not run — the guard checked nothing" }

        if (!awaitCollected(modules)) {
            fail(
                "${modules.count { it.get() != null }} compiled IrModuleFragment(s) still reachable after the " +
                    "compilation finished — the plugin retains IR (Kotlin daemon OOM, #110).\n" +
                    "Plugin ThreadLocals still bound on this thread: ${boundPluginThreadLocals().ifEmpty { listOf("none") }}"
            )
        }
    }

    private fun awaitCollected(refs: List<WeakReference<IrModuleFragment>>): Boolean {
        repeat(20) {
            if (refs.all { it.get() == null }) return true
            System.gc()
            Thread.sleep(50)
        }
        return refs.all { it.get() == null }
    }

    /** Static ThreadLocal fields under `org.koin.compiler.plugin` that still hold a value on this thread. */
    private fun boundPluginThreadLocals(): List<String> {
        val root = File(KoinIrExtension::class.java.protectionDomain.codeSource.location.toURI())
        val classFiles: Sequence<String> = if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
        } else {
            JarFile(root).use { jar -> jar.entries().asSequence().map { it.name }.toList() }.asSequence()
        }
        return classFiles
            .filter { it.startsWith("org/koin/compiler/plugin/") && it.endsWith(".class") }
            .mapNotNull { path ->
                val name = path.removeSuffix(".class").replace('/', '.')
                runCatching { Class.forName(name, false, KoinIrExtension::class.java.classLoader) }.getOrNull()
            }
            .flatMap { c -> runCatching { c.declaredFields.toList() }.getOrDefault(emptyList()).map { c to it } }
            .filter { (_, f) -> Modifier.isStatic(f.modifiers) && ThreadLocal::class.java.isAssignableFrom(f.type) }
            .mapNotNull { (c, f) ->
                val tl = runCatching { f.isAccessible = true; f.get(null) as ThreadLocal<*> }.getOrNull() ?: return@mapNotNull null
                val value = tl.get()
                val empty = value == null || (value is Collection<*> && value.isEmpty()) || (value is Map<*, *> && value.isEmpty())
                if (empty) null else "${c.simpleName}.${f.name}"
            }
            .toList()
    }

    private class ModuleCaptureConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
        override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
            module: TestModule,
            configuration: CompilerConfiguration,
        ) {
            IrGenerationExtension.registerExtension(object : IrGenerationExtension {
                override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                    captured += WeakReference(moduleFragment)
                }
            })
        }
    }

    private companion object {
        const val TEST_FILE = "koin-compiler-plugin/testData/registry/all_registries_filled.kt"
        val captured = java.util.Collections.synchronizedList(mutableListOf<WeakReference<IrModuleFragment>>())
    }
}
