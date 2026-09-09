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
 * Two separate concerns, deliberately decoupled:
 *
 * - **Adapter selection** is by line: the adapter with the highest Kotlin line at
 *   or below the running compiler's line (pre-releases select their line's adapter,
 *   2.4.0-Beta1 carries the 2.4 ABI). A compiler older than every entry is
 *   unsupported. Several verified versions may share one adapter class.
 * - **Trust** is by exact release: a version is verified only when it matches a
 *   registry entry's `major.minor.patch`. Anything else warns, even inside a
 *   registered line.
 *
 * Conflating the two is what let Kotlin 2.4.20 through silently (GH #89, #99):
 * `major.minor` looked like a safe trust unit, but Kotlin ships feature releases in
 * the `.20` patch slot, so 2.4.20 removed four compiler APIs the plugin binds while
 * 2.4.10 removed none. Registry entries are earned by a green
 * `tools/abi-check/check-kotlin-abi.sh <version>` run, per CLAUDE.md's version-gate
 * policy — never by assuming a line stays compatible.
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
            loaded ?: load().let { selection ->
                // This path bypasses the registrar's messageCollector — don't
                // swallow version warnings, surface them on stderr.
                selection.warnings.forEach { System.err.println("warning: $it") }
                selection.adapter
                    ?: error(selection.error ?: "Koin compiler plugin: no compatible Kotlin version adapter")
            }
        }

    /** Which registry entry to use, and any warnings/error — no classloading. Pure, for testing. */
    internal data class Decision(
        val entry: Pair<KotlinReleaseVersion, String>?,
        val warnings: List<String>,
        val error: String?,
    )

    internal fun decide(registry: List<Pair<KotlinReleaseVersion, String>>, compilerVersion: String): Decision {
        val newest = registry.last()
        val warnings = mutableListOf<String>()

        val current = KotlinReleaseVersion.parseOrNull(compilerVersion)
        val entry = when {
            current == null -> {
                warnings += "Koin compiler plugin: unrecognized Kotlin version '$compilerVersion' — using the adapter for Kotlin ${newest.first.raw} (newest available). Verified versions: ${supportedList(registry)}."
                newest
            }
            else -> registry.lastOrNull { current.lineAtLeast(it.first) }
                ?: return Decision(
                    null, warnings,
                    "Koin compiler plugin: Kotlin $compilerVersion is older than the oldest supported version (${registry.first().first.raw}). " +
                        "Upgrade Kotlin or use a koin-compiler-plugin release matching your Kotlin version. Verified versions: ${supportedList(registry)}.",
                )
        }

        if (current != null && registry.none { it.first.sameRelease(current) }) {
            val proceedingWith = entry.first.raw
            val relation =
                if (current.lineAtLeast(newest.first)) "newer than the newest verified version (${newest.first.raw})"
                else "not among the verified versions"
            warnings += "Koin compiler plugin: Kotlin $compilerVersion is $relation — proceeding with the $proceedingWith adapter. " +
                "If compilation fails, check for a koin-compiler-plugin update. Verified versions: ${supportedList(registry)}."
        }

        return Decision(entry, warnings, null)
    }

    fun load(compilerVersion: String = KotlinCompilerVersion.VERSION ?: "unknown"): Selection {
        val registry = readRegistry()
        if (registry.isEmpty()) {
            return Selection(null, emptyList(), "Koin compiler plugin: no Kotlin version adapters found on the plugin classpath ($REGISTRY_PATH)")
        }
        val decision = decide(registry, compilerVersion)
        val entry = decision.entry ?: return Selection(null, decision.warnings, decision.error)

        return try {
            val adapter = Class.forName(entry.second, true, KotlinAdapterLoader::class.java.classLoader)
                .getDeclaredConstructor()
                .newInstance() as KotlinVersionAdapter
            loaded = adapter
            Selection(adapter, decision.warnings, null)
        } catch (e: Throwable) {
            Selection(null, decision.warnings, "Koin compiler plugin: failed to load Kotlin version adapter '${entry.second}' for Kotlin $compilerVersion: $e")
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
