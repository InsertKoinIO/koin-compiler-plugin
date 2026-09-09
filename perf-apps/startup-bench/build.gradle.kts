plugins {
    kotlin("jvm")
    id("io.insert-koin.compiler.plugin")
    id("me.champeau.jmh")
}

// Axes of the A/B: koin-core version (published 4.2.2 vs local 4.2.3-perfs) and the generated
// registration shape (directRegistration on/off). Both are Gradle properties so one build script
// serves every combination and nothing else differs between runs.
val koinVersion: String = (project.findProperty("koinVersion") as? String) ?: "4.2.2"
val useDirectRegistration: Boolean = (project.findProperty("directRegistration") as? String)?.toBoolean() ?: true
val useVerifyKeys: Boolean = (project.findProperty("verifyGeneratedKeys") as? String)?.toBoolean() ?: false

kotlin { jvmToolchain(21) }

dependencies {
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("io.insert-koin:koin-annotations:$koinVersion")
}

// Script-level names differ from the extension's own properties on purpose: inside the block,
// `directRegistration` on the right-hand side would resolve to the extension property itself.
koinCompiler {
    directRegistration.set(useDirectRegistration)
    verifyGeneratedKeys.set(useVerifyKeys)
    logSeverity.set("info")
}

// Same JMH shape as Metro's startup-jvm: AverageTime, 2 forks, 4 x 1s warmup, 10 x 1s measure.
jmh {
    warmupIterations.set(4)
    iterations.set(10)
    fork.set(2)
    timeUnit.set("ms")
    benchmarkMode.set(listOf("avgt"))
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
    jvmArgsAppend.set(listOf("-Dkoin.version=$koinVersion", "-Dkoin.directRegistration=$useDirectRegistration"))
}

tasks.register("printConfig") {
    doLast { println("koinVersion=$koinVersion directRegistration=$useDirectRegistration verifyGeneratedKeys=$useVerifyKeys") }
}
