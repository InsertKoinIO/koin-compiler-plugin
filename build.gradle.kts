plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.build.config)
    alias(libs.plugins.binary.compatibility.validator) apply false
    alias(libs.plugins.nmcp)
    alias(libs.plugins.detekt) apply false
}

val pluginVersion: String by project

// NMCP credential helpers
fun getRepositoryUsername(): String =
    findProperty("OSSRH_USERNAME")?.toString() ?: System.getenv("OSSRH_USERNAME") ?: ""

fun getRepositoryPassword(): String =
    findProperty("OSSRH_PASSWORD")?.toString() ?: System.getenv("OSSRH_PASSWORD") ?: ""

// NMCP configuration for Maven Central publishing
nmcpAggregation {
    centralPortal {
        username.set(getRepositoryUsername())
        password.set(getRepositoryPassword())
        // publish manually from the portal
        publishingType = "USER_MANAGED"
    }

    // Publish all projects that apply the 'maven-publish' plugin
    publishAllProjectsProbablyBreakingProjectIsolation()
}

// Workaround: Manually add koin-compiler-plugin to aggregation since NMCP doesn't detect it
// (it uses `apply(from=...)` for maven-publish which is evaluated after NMCP scans)
afterEvaluate {
    tasks.named<Zip>("nmcpZipAggregation") {
        dependsOn(":koin-compiler-plugin:publishAllPublicationsToNmcpRepository")
        // Include the compiler plugin's nmcp output in the aggregation zip
        from(project(":koin-compiler-plugin").layout.buildDirectory.dir("nmcp/m2"))
    }
}

allprojects {
    group = "io.insert-koin"
    version = pluginVersion
}

// Not `--auto-correct`: that option lands on the aggregate task, which analyses nothing.
val detektAutoCorrect = providers.gradleProperty("detektAutoCorrect").isPresent
val detektConfigDir = layout.projectDirectory.dir("config/detekt")

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "dev.detekt")

        extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            parallel = false
            autoCorrect = detektAutoCorrect
            config.from(detektConfigDir.file("detekt.yml"))
            baseline = detektConfigDir.file("baseline/${path.removePrefix(":").replace(':', '-')}.xml").asFile
        }

        dependencies {
            "detektPlugins"(rootProject.libs.detekt.ktlint)
        }

        tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
            exclude { it.file.invariantSeparatorsPath.contains("/testData/") }
        }

        // Only the type-resolving tasks get a compile classpath; the rest skip type-aware rules silently.
        val typeResolvingDetekt = tasks.withType<dev.detekt.gradle.Detekt>().matching {
            it.name != "detekt" && !it.name.endsWith("SourceSet")
        }
        tasks.named("detekt") {
            enabled = false
            dependsOn(typeResolvingDetekt)
        }
        tasks.named("check") { dependsOn("detekt") }
    }
}
