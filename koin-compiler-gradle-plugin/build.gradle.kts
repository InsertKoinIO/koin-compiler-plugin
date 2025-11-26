plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.build.config)
    id("java-gradle-plugin")
    id("com.gradle.plugin-publish") version "1.2.1"
}

val pluginVersion: String by project

group = "io.insert-koin"
version = pluginVersion

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        java.setSrcDirs(listOf("test"))
        resources.setSrcDirs(listOf("testResources"))
    }
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))

    testImplementation(kotlin("test-junit5"))
}

// Plugin coordinates - used by BuildConfig and in the plugin application
val pluginId = "io.insert-koin.compiler.plugin"
val pluginGroup = "io.insert-koin"

buildConfig {
    packageName("io.insert_koin.compiler.plugin")

    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"$pluginId\"")
    buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"$pluginGroup\"")
    buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"koin-compiler-plugin\"")
    buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"$pluginVersion\"")
}

gradlePlugin {
    website = "https://insert-koin.io/"
    vcsUrl = "https://github.com/InsertKoinIO/koin-compiler-plugin"

    plugins {
        create("KoinCompilerPlugin") {
            id = pluginId
            displayName = "Koin Compiler Plugin"
            description = "Kotlin compiler plugin for Koin dependency injection - compile-time DSL transformation"
            tags = listOf("koin", "kotlin", "dependency-injection", "di", "injection", "kotlin-multiplatform" ,"compiler-plugin")
            implementationClass = "org.koin.compiler.plugin.KoinGradlePlugin"
        }
    }
}

// Maven Central publishing
apply(from = file("../gradle/publish-gradle-plugin.gradle.kts"))
