// Version-neutral adapter API: interface, loader, version parser.
// Compiled against the OLDEST supported kotlin-compiler (the floor) so its
// bytecode links on every supported version. Pinned explicitly — independent
// of the root `kotlin` version used to build the plugin itself.
plugins {
    kotlin("jvm")
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        java.setSrcDirs(listOf("test"))
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler:2.3.20") // floor — do not use kotlin("compiler")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
