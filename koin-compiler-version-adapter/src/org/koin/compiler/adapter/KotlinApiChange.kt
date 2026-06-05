package org.koin.compiler.adapter

/**
 * Documents why a member of [KotlinVersionAdapter] exists: which Kotlin compiler
 * version changed the underlying API, and how.
 *
 * Every adapter member must carry this annotation — the adapter surface should only
 * contain operations whose compiler API differs between supported Kotlin versions.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class KotlinApiChange(
    /** Kotlin version that introduced the incompatibility (e.g. "2.4.0"). */
    val inVersion: String,
    val kind: Kind,
    val note: String = "",
) {
    enum class Kind {
        /** API was removed. */
        REMOVED,

        /** API was renamed or moved. */
        RENAMED,

        /** Source-compatible but binary contract changed (recompilation required). */
        SIGNATURE,
    }
}
