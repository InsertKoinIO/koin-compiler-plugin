package org.koin.compiler.adapter

import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.util.Properties

/**
 * Selects and instantiates the [KotlinVersionAdapter] matching the running compiler.
 *
 * Adapters are declared in `META-INF/koin/kotlin-version-adapters.properties`
 * (`<kotlin-version>=<adapter class>`); only the selected class is ever loaded,
 * so adapter bytecode compiled against other compiler versions stays untouched
 * on the classpath.
 *
 * Selection picks the adapter with the highest Kotlin line at or below the
 * running compiler's line (pre-releases select their line's adapter: 2.4.0-Beta1
 * carries the 2.4 ABI). A compiler newer than every adapter gets the newest
 * adapter plus a warning; one older than every adapter is unsupported.
 */
object KotlinAdapterLoader {

    private const val REGISTRY_PATH = "META-INF/koin/kotlin-version-adapters.properties"

    data class Selection(
        val adapter: KotlinVersionAdapter?,
        val warnings: List<String>,
        val error: String?,
    )

    @Volatile
    private var loaded: KotlinVersionAdapter? = null

    /**
     * The adapter for the running compiler. Normally initialized by the registrar
     * calling [load] (which also surfaces warnings); self-initializes for entry
     * paths that bypass plugin registration (e.g. compiler test frameworks that
     * register extensions directly).
     */
    val current: KotlinVersionAdapter
        get() = loaded ?: synchronized(this) {
            loaded ?: load().let {
                it.adapter ?: error(it.error ?: "Koin compiler plugin: no compatible Kotlin version adapter")
            }
        }

    fun load(compilerVersion: String = KotlinCompilerVersion.VERSION ?: "unknown"): Selection {
        val registry = readRegistry()
        if (registry.isEmpty()) {
            return Selection(null, emptyList(), "Koin compiler plugin: no Kotlin version adapters found on the plugin classpath ($REGISTRY_PATH)")
        }
        val newest = registry.last()
        val warnings = mutableListOf<String>()

        val current = KotlinReleaseVersion.parseOrNull(compilerVersion)
        val entry = when {
            current == null -> {
                warnings += "Koin compiler plugin: unrecognized Kotlin version '$compilerVersion' — using the adapter for Kotlin ${newest.first.raw} (newest available). Supported versions: ${supportedList(registry)}."
                newest
            }
            else -> registry.lastOrNull { current.lineAtLeast(it.first) }
                ?: return Selection(
                    null, warnings,
                    "Koin compiler plugin: Kotlin $compilerVersion is older than the oldest supported version (${registry.first().first.raw}). " +
                        "Upgrade Kotlin or use a koin-compiler-plugin release matching your Kotlin version. Supported versions: ${supportedList(registry)}.",
                )
        }

        if (current != null && !newest.first.lineAtLeast(current)) {
            warnings += "Koin compiler plugin: Kotlin $compilerVersion is newer than the newest tested version (${newest.first.raw}) — proceeding with the ${newest.first.raw} adapter. " +
                "If compilation fails, check for a koin-compiler-plugin update. Supported versions: ${supportedList(registry)}."
        }

        return try {
            val adapter = Class.forName(entry.second, true, KotlinAdapterLoader::class.java.classLoader)
                .getDeclaredConstructor()
                .newInstance() as KotlinVersionAdapter
            loaded = adapter
            Selection(adapter, warnings, null)
        } catch (e: Throwable) {
            Selection(null, warnings, "Koin compiler plugin: failed to load Kotlin version adapter '${entry.second}' for Kotlin $compilerVersion: $e")
        }
    }

    /** Registry entries sorted by Kotlin version, oldest first. */
    private fun readRegistry(): List<Pair<KotlinReleaseVersion, String>> {
        val resource = KotlinAdapterLoader::class.java.classLoader.getResourceAsStream(REGISTRY_PATH)
            ?: return emptyList()
        val properties = Properties().apply { resource.use { load(it) } }
        return properties.entries
            .mapNotNull { (key, value) ->
                KotlinReleaseVersion.parseOrNull(key.toString())?.let { it to value.toString() }
            }
            .sortedBy { it.first }
    }

    private fun supportedList(registry: List<Pair<KotlinReleaseVersion, String>>): String =
        registry.joinToString(", ") { it.first.raw }
}
