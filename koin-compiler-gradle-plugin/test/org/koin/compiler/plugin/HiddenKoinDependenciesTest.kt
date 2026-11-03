package org.koin.compiler.plugin

import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenKoinDependenciesTest {

    private val dir = createTempDirectory("koin-hidden").toFile()

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun file(name: String, vararg entries: Pair<String, ByteArray>) =
        File(dir, name).apply { writeBytes(zipBytes(*entries)) }

    private val hintClass = "org/koin/plugin/hints/Koin_dsl_hints_dataKt.class" to ByteArray(1)
    private val plainClass = "com/example/Repo.class" to ByteArray(1)

    @Test
    fun `severity ignores case and surrounding spaces`() {
        assertEquals(HiddenDependencySeverity.ERROR, HiddenDependencySeverity.parse(" Error "))
        assertEquals(HiddenDependencySeverity.NONE, HiddenDependencySeverity.parse("NONE"))
        assertEquals(HiddenDependencySeverity.WARNING, HiddenDependencySeverity.parse("warning"))
    }

    @Test
    fun `an unknown severity fails with the supported values`() {
        val error = assertFailsWith<GradleException> { HiddenDependencySeverity.parse("off") }
        assertTrue("\"off\"" in error.message.orEmpty())
        assertTrue("\"warning\", \"error\" or \"none\"" in error.message.orEmpty())
    }

    @Test
    fun `a jar with hint classes contributes, a jar without doesn't`() {
        assertTrue(containsKoinHints(file("data.jar", hintClass, plainClass)))
        assertFalse(containsKoinHints(file("plain.jar", plainClass)))
    }

    @Test
    fun `an AAR is read through its classes jar and its libs jars`() {
        assertTrue(containsKoinHints(file("a.aar", "classes.jar" to zipBytes(hintClass))))
        val hintsInLibs = file("b.aar", "classes.jar" to zipBytes(plainClass), "libs/x.jar" to zipBytes(hintClass))
        assertTrue(containsKoinHints(hintsInLibs))
        assertFalse(containsKoinHints(file("c.aar", "classes.jar" to zipBytes(plainClass))))
    }

    @Test
    fun `a classes directory contributes when it holds hint classes`() {
        val classes = File(dir, "classes").apply { File(this, "org/koin/plugin/hints").mkdirs() }
        assertFalse(containsKoinHints(classes))
        File(classes, "org/koin/plugin/hints/Koin_hints_x.class").writeBytes(ByteArray(1))
        assertTrue(containsKoinHints(classes))
    }

    @Test
    fun `an unreadable archive doesn't contribute`() {
        assertFalse(containsKoinHints(File(dir, "broken.jar").apply { writeText("not a zip") }))
    }
}
