package org.koin.compiler.plugin.ir

/**
 * Deterministic, collision-resistant, length-bounded file name for a synthetic hint file (#75).
 *
 * Two distinct call sites building a hint file name the same lossy way -- every non-alphanumeric
 * character collapsing to `_` -- could collide: `p.q_r.mod` and `p.q.r_mod` both sanitize to
 * `p_q_r_mod`. And an unbounded name (module id + FIR module name + a deeply-nested package)
 * can exceed macOS's ~255-byte path-component limit, failing the write with `Permission denied`.
 *
 * Both problems share one fix: hash the ORIGINAL, untruncated identity -- not a lossy sanitized
 * form of it. Two identities that sanitize to the same readable string still hash differently
 * (their raw bytes differ), and the hash's fixed length caps the total name regardless of how
 * deep the input package/module nesting goes.
 *
 * Nothing reconstructs a hint FILE name from scratch -- unlike hint FUNCTION names (built via
 * [KoinPluginConstants.flattenFqNameForHint]), which cross-module consumers independently
 * rebuild to look themselves up, and which MUST stay frozen for that reason. A file name is only
 * ever read back by the same compilation that wrote it, so this is free to change.
 *
 * This does NOT close the function-name collision surface -- two distinct module ids can still
 * flatten to the same `flattenFqNameForHint` output and collide as FUNCTION names inside two now
 * distinctly-named files. That's guarded separately: see `KOIN-D008` / `DuplicateModuleHintIdentity`
 * in `DslHintGenerator`, which detects it before generation rather than renaming the frozen encoder.
 */
internal object HintFileNaming {
    /** Cap on the human-readable portion; the hash suffix's length is what actually bounds the
     *  total name, so this only needs to keep debugging comfortable, not do any real bounding. */
    private const val READABLE_MAX_LENGTH = 48

    /**
     * Build a hint file name from a short constant [tag] (e.g. "koin_dsl_hints_") and one or more
     * raw identity components (a module id, a FIR module name, a target FQN, ...). Null/blank
     * components are dropped. ALL components feed the readable portion (truncated to a fixed
     * budget -- so a Gradle module id / KMP target name stays visible for debugging, as it was
     * before this hashing scheme) AND the hash (untruncated -- two identities that only differ
     * past the readable budget still get distinct names, since the hash is what actually
     * guarantees no collision, not the readable text).
     */
    fun fileName(tag: String, vararg identityParts: String?, extension: String = ".kt"): String {
        val parts = identityParts.filterNotNull().filter { it.isNotEmpty() }
        val fullIdentity = parts.joinToString(" ")
        val readable = buildString {
            for (part in parts) {
                if (length >= READABLE_MAX_LENGTH) break
                if (isNotEmpty()) append('_')
                for (ch in part) {
                    if (length >= READABLE_MAX_LENGTH) break
                    append(if (ch.isLetterOrDigit()) ch else '_')
                }
            }
        }.trim('_')
        val hash = hash64(fullIdentity)
        return if (readable.isEmpty()) "$tag$hash$extension" else "$tag${readable}_$hash$extension"
    }

    /**
     * 64-bit FNV-1a, hex-encoded (16 chars). Deliberately not [String.hashCode] -- that's 32-bit,
     * and at monorepo scale (thousands of hint files) birthday-bound collision odds on a 32-bit
     * space stop being negligible. 64 bits keeps them negligible at any realistic scale.
     */
    private fun hash64(input: String): String {
        var hash = -3750763034362895579L // FNV offset basis, as a signed Long
        for (b in input.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 1099511628211L // FNV prime
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}
