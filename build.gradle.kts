plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.build.config)
    alias(libs.plugins.binary.compatibility.validator) apply false
    alias(libs.plugins.nmcp)
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

subprojects {
    group = "io.insert-koin"
    version = pluginVersion
}
