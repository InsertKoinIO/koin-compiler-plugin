package org.jetbrains.kotlin.compiler.plugin.template.services

import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer

/**
 * Custom test directives for exercising Koin compiler plugin options that aren't exposed
 * any other way from `testData/box` sources (which have no `build.gradle.kts` to configure).
 */
object KoinTestDirectives : SimpleDirectivesContainer() {
    /** `// JSR330_DISABLED` — runs the test with `jsr330 = false` (see [KoinTestDirectives]). */
    val JSR330_DISABLED by directive(
        "Disables jakarta.inject/javax.inject (JSR-330) annotation processing for this test"
    )
}
