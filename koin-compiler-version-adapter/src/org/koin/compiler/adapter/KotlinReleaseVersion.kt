package org.koin.compiler.adapter

/**
 * Minimal Kotlin version parser for adapter selection.
 *
 * Parses version strings as reported by the running compiler (e.g. "2.3.20",
 * "2.4.0-Beta1", "2.4.0-RC2", "2.4.20-dev-835") into a comparable form.
 *
 * Adapter selection compares the numeric release line (major.minor.patch) only:
 * a pre-release of a line (2.4.0-Beta1) carries that line's compiler ABI, so it
 * selects the same adapter as the stable release. [maturity] is kept for
 * diagnostics and tie-breaking in messages, not for selection.
 */
data class KotlinReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val maturity: Maturity,
    val raw: String,
) : Comparable<KotlinReleaseVersion> {

    enum class Maturity { SNAPSHOT, DEV, MILESTONE, ALPHA, BETA, RC, STABLE }

    /** Numeric release-line comparison; maturity breaks ties within the same line. */
    override fun compareTo(other: KotlinReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.maturity })

    /** Same numeric line regardless of maturity (2.4.0-Beta1 ~ 2.4.0). */
    fun sameLineAs(other: KotlinReleaseVersion): Boolean =
        major == other.major && minor == other.minor && patch == other.patch

    /** True if this version's line is at or above [other]'s line. */
    fun lineAtLeast(other: KotlinReleaseVersion): Boolean =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }) >= 0

    override fun toString(): String = raw

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:-([A-Za-z]+)(\d*))?(?:-.*)?$""")

        /** Parses [version], or returns null if the string is not a recognizable Kotlin version. */
        fun parseOrNull(version: String): KotlinReleaseVersion? {
            val match = PATTERN.matchEntire(version.trim()) ?: return null
            val (major, minor, patch, qualifier, _) = match.destructured
            val maturity = when (qualifier.lowercase()) {
                "" -> Maturity.STABLE
                "snapshot" -> Maturity.SNAPSHOT
                "dev" -> Maturity.DEV
                "m" -> Maturity.MILESTONE
                "alpha" -> Maturity.ALPHA
                "beta" -> Maturity.BETA
                "rc" -> Maturity.RC
                else -> Maturity.DEV // unknown qualifier: treat as a dev/preview build
            }
            return KotlinReleaseVersion(
                major = major.toInt(),
                minor = minor.toInt(),
                patch = patch.ifEmpty { "0" }.toInt(),
                maturity = maturity,
                raw = version,
            )
        }
    }
}
