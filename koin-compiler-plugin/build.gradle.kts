plugins {
    kotlin("jvm")
    `java-test-fixtures`
    id("com.github.gmazzo.buildconfig")
    idea
    `maven-publish`
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    testFixtures {
        java.setSrcDirs(listOf("test-fixtures"))
    }
    test {
        java.setSrcDirs(listOf("test", "test-gen"))
        resources.setSrcDirs(listOf("testData"))
    }
}

idea {
    module.generatedSourceDirs.add(projectDir.resolve("test-gen"))
}

val pluginVersion: String by project
val annotationsRuntimeClasspath: Configuration by configurations.creating { isTransitive = true }

dependencies {
    compileOnly(kotlin("compiler"))

    testFixturesApi(kotlin("test-junit5"))
    testFixturesApi(kotlin("compiler-internal-test-framework"))
    testFixturesApi(kotlin("compiler"))

    // Koin libraries (koin-core for basic DSL, koin-annotations for plugin functions)
    annotationsRuntimeClasspath(libs.koin.core)
    annotationsRuntimeClasspath(libs.koin.annotations)

    // Kotzilla SDK for @Monitor annotation testing
    annotationsRuntimeClasspath(libs.kotzilla.core)

    // Dependencies required to run the internal test framework.
    testRuntimeOnly(libs.junit)
    testRuntimeOnly(kotlin("reflect"))
    testRuntimeOnly(kotlin("test"))
    testRuntimeOnly(kotlin("script-runtime"))
    testRuntimeOnly(kotlin("annotations-jvm"))
}

buildConfig {
    useKotlinOutput {
        internalVisibility = true
    }

    packageName("io.insert_koin.compiler.plugin")
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"io.insert-koin.compiler.plugin\"")
}

tasks.test {
    dependsOn(annotationsRuntimeClasspath)

    useJUnitPlatform()
    workingDir = rootDir

    systemProperty("annotationsRuntime.classpath", annotationsRuntimeClasspath.asPath)

    // Properties required to run the internal test framework.
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)

    // Enable test data overwriting when running with -Pupdate.testdata=true
    if (project.hasProperty("update.testdata")) {
        systemProperty("overwrite.output", "true")
        environment("OVERWRITE_EXPECTED_OUTPUT", "true")
    }
}

kotlin {
    // Pin JDK 17 so the produced bytecode is stable across developer machines.
    // Without this, the class file version is whatever the host JDK is running Gradle,
    // which drifts (e.g. building on JDK 21 ships class files consumers on JDK 17 can't load).
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
        // Allow using deprecated compiler APIs
        freeCompilerArgs.addAll(
            "-Xsuppress-version-warnings",
            "-Xskip-prerelease-check",
            "-Xallow-kotlin-package",
            "-Xcontext-parameters"
        )
        // Don't treat warnings as errors
        allWarningsAsErrors.set(false)
    }
}

val generateTests by tasks.registering(JavaExec::class) {
    inputs.dir(layout.projectDirectory.dir("testData"))
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.projectDirectory.dir("test-gen"))
        .withPropertyName("generatedTests")

    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.compiler.plugin.template.GenerateTestsKt")
    workingDir = rootDir
}

tasks.compileTestKotlin {
    dependsOn(generateTests)
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
    val path = project.configurations
        .testRuntimeClasspath.get()
        .files
        .find { """$jarName-\d.*jar""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: return
    systemProperty(propName, path)
}

/**
 * Fails the build if compiled bytecode is not Java 17.
 *
 * Class file major version 61 = Java 17, 65 = Java 21, etc. We pin JDK 17 via
 * `jvmToolchain(17)` + `jvmTarget.set(JVM_17)` above, but a downstream edit that
 * removes either of those would silently produce a different target. This task
 * reads a known compiled class and asserts the major version — cheap insurance.
 */
val verifyJvmTarget by tasks.registering {
    val expectedMajor = 61  // Java 17
    dependsOn(tasks.compileKotlin)
    val probeFile = layout.buildDirectory
        .file("classes/kotlin/main/org/koin/compiler/plugin/KoinPluginConstants.class")
    inputs.file(probeFile)
    doLast {
        val file = probeFile.get().asFile
        require(file.exists()) { "Expected class file not found: $file" }
        // Class file format: magic (0xCAFEBABE, 4 bytes) + minor (2 bytes) + major (2 bytes)
        file.inputStream().use { input ->
            val bytes = ByteArray(8)
            require(input.read(bytes) == 8) { "Truncated class file: $file" }
            val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
            check(major == expectedMajor) {
                "Expected Java $expectedMajor (class file major $expectedMajor) but got major $major. " +
                    "Check jvmToolchain / jvmTarget in ${project.path}/build.gradle.kts."
            }
        }
    }
}

tasks.named("check") { dependsOn(verifyJvmTarget) }

// Maven Central publishing
apply(from = file("../gradle/publish-compiler.gradle.kts"))
