package org.koin.compiler.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// Package selection for @ComponentScan values (#107).
class ComponentScanPackageMatcherTest {

    private fun assertMatches(scanPackage: String, expected: Map<String, Boolean>) {
        val actual = expected.keys.associateWith { ComponentScanPackageMatcher.matches(it, scanPackage) }
        assertEquals(expected, actual, "scan package \"$scanPackage\"")
    }

    @Test
    fun `plain value covers the package and its subpackages`() {
        assertMatches(
            "com.example",
            mapOf("com.example" to true, "com.example.a" to true, "com.examplex" to false, "com" to false),
        )
    }

    @Test
    fun `empty value covers only the root package`() {
        assertMatches("", mapOf("" to true, "com" to false))
    }

    @Test
    fun `double star after a segment name covers the package and its subpackages`() {
        assertMatches(
            "com.example**",
            mapOf("com.example" to true, "com.example.a.b" to true, "com.examplex" to false, "com.examplex.a" to false),
        )
    }

    @Test
    fun `double star after a segment name inside the pattern keeps the segment boundary`() {
        assertMatches(
            "com.example**.infra",
            mapOf("com.example.infra" to true, "com.example.a.infra" to true, "com.examplex.infra" to false),
        )
    }

    @Test
    fun `trailing double star segment covers subpackages only`() {
        assertMatches(
            "com.example.**",
            mapOf("com.example" to false, "com.example.a" to true, "com.example.a.b" to true, "com.examplex.a" to false),
        )
    }

    @Test
    fun `double star segment in the middle spans one or more segments`() {
        assertMatches(
            "com.**.infra",
            mapOf(
                "com.infra" to false,
                "com.a.infra" to true,
                "com.a.b.infra" to true,
                "com.a.infra.sub" to false,
                "com.a.infrax" to false,
            ),
        )
    }

    @Test
    fun `single star matches exactly one segment`() {
        assertMatches(
            "com.*.svc",
            mapOf("com.svc" to false, "com.a.svc" to true, "com.a.b.svc" to false, "com.a.svc.sub" to false),
        )
    }

    @Test
    fun `single star inside a segment stays within that segment`() {
        assertMatches(
            "com.**.service.*data",
            mapOf("com.a.service.userdata" to true, "com.a.service.data" to true, "com.a.service.user.data" to false),
        )
    }

    @Test
    fun `non-letter characters in a value are matched literally`() {
        assertMatches("com.a_b.**", mapOf("com.a_b.c" to true, "com.aXb.c" to false))
    }
}
