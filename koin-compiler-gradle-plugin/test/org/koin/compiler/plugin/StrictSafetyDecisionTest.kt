package org.koin.compiler.plugin

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrictSafetyDecisionTest {

    private val project: Project = ProjectBuilder.builder().build()
    private var sourceReads = 0

    private fun decision(
        source: String = "fun main() { startKoin { } }",
        strictSafety: Boolean? = null,
        forceOff: Boolean = false,
        compileSafety: Boolean = true,
    ): StrictSafetyDecision {
        val dir = createTempDirectory("koin-sources").toFile()
        File(dir, "App.kt").writeText(source)
        return StrictSafetyDecision(
            projectPath = ":app",
            strictSafety = project.objects.property(Boolean::class.java).apply { strictSafety?.let(::set) },
            strictSafetyForceOff = project.provider { forceOff },
            compileSafety = project.provider { compileSafety },
            sources = project.files(
                project.provider {
                    sourceReads++
                    dir
                },
            ),
        )
    }

    @Test
    fun `sources are not read until the decision is evaluated`() {
        val decision = decision()
        assertEquals(0, sourceReads, "configuration time must not resolve the sources")
        assertTrue(decision.isActive())
        assertTrue(sourceReads > 0)
    }

    @Test
    fun `the scan runs once per decision`() {
        val decision = decision()
        decision.isActive()
        val readsAfterFirst = sourceReads
        decision.isActive()
        assertEquals(readsAfterFirst, sourceReads)
    }

    @Test
    fun `detected entry point enables it`() = assertTrue(decision().isActive())

    @Test
    fun `no entry point leaves it off`() = assertFalse(decision(source = "class Plain").isActive())

    @Test
    fun `explicit true wins without scanning`() {
        assertTrue(decision(source = "class Plain", strictSafety = true).isActive())
        assertEquals(0, sourceReads)
    }

    @Test
    fun `explicit false is ignored when an entry point is detected`() =
        assertTrue(decision(strictSafety = false).isActive())

    @Test
    fun `explicit false with forceOff disables it`() =
        assertFalse(decision(strictSafety = false, forceOff = true).isActive())

    @Test
    fun `forceOff alone does not disable it`() = assertTrue(decision(forceOff = true).isActive())

    @Test
    fun `compileSafety off disables it without scanning`() {
        assertFalse(decision(compileSafety = false).isActive())
        assertEquals(0, sourceReads)
    }
}
