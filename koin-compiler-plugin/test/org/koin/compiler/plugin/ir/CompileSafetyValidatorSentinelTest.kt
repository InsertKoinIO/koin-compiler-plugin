package org.koin.compiler.plugin.ir

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression sentinel for the 1.1.0 A2 removal (see docs/COMPILE_SAFETY_A3_PLAN.md).
 *
 * A2 (per-module isolated validation) was deleted because a module validated in isolation
 * cannot know how it will actually be wired into a larger app — a measured false positive
 * (a module failed alone on a dependency provided by a non-dependency peer; adding only the
 * Gradle dependency edge, no Koin change, made it pass). If this test goes red, someone has
 * reintroduced a per-module validation entry point on [CompileSafetyValidator] — almost
 * certainly to "fix" a leaf false negative the same way A2 used to, which silently
 * reintroduces the exact unsoundness this release removed. The correct fix for a leaf false
 * negative is an entry point in that compilation (see [CompileSafetyValidator]'s class doc),
 * not a new per-module validate() method.
 */
class CompileSafetyValidatorSentinelTest {

    @Test
    fun `no per-module validate method exists on CompileSafetyValidator`() {
        val methodNames = CompileSafetyValidator::class.java.declaredMethods.map { it.name }.toSet()

        // A2's orchestrator method was literally named `validate` (distinct from `validateFullGraph`,
        // A3's sole remaining entry point).
        assertFalse(
            "validate" in methodNames,
            "CompileSafetyValidator gained a `validate` method — this looks like A2 (per-module " +
                "isolated validation) being reintroduced. A module validated alone cannot know how " +
                "it will be wired into a larger app (see docs/COMPILE_SAFETY_A3_PLAN.md); the fix " +
                "for a leaf false negative is an entry point, not per-module validation.",
        )

        // The deferral machinery A2 needed (defer an unresolved binding pending a later, more
        // authoritative pass) has no reason to exist with only one verifier.
        assertFalse(
            "flushDeferred" in methodNames,
            "CompileSafetyValidator gained a `flushDeferred` method — the A2 deferral machinery " +
                "(KOIN-W002, DeferredRequirement) was deleted alongside A2 itself and should not " +
                "come back without also reintroducing A2.",
        )

        // A3's full-graph verifier is the one thing that should still be here.
        assertTrue(
            "validateFullGraph" in methodNames,
            "CompileSafetyValidator lost its `validateFullGraph` method — A3 is meant to be the " +
                "sole verifier; something removed it without a replacement.",
        )
    }
}
